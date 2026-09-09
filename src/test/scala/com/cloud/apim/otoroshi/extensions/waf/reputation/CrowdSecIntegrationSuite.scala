package com.cloud.apim.otoroshi.extensions.waf.reputation

import com.cloud.apim.otoroshi.extensions.waf.entities.CrowdSecBouncer
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName
import play.api.Logger
import play.api.libs.json.*

import java.net.URI
import java.net.http.{HttpRequest, HttpResponse, HttpClient as JdkClient}
import java.time.Duration as JDuration
import scala.concurrent.duration.*
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.jdk.CollectionConverters.*

/** Minimal client over the JDK's HTTP stack, so the suite needs no Otoroshi runtime. */
class JdkHttpClient(using ec: ExecutionContext) extends ReputationHttpClient {

  private val client = JdkClient.newBuilder().connectTimeout(JDuration.ofSeconds(10)).build()

  override def call(request: HttpCall): Future[HttpResult] = Future {
    val builder = HttpRequest
      .newBuilder(URI.create(request.url))
      .timeout(JDuration.ofMillis(request.timeout.toMillis))
    request.headers.foreach { case (name, value) => builder.header(name, value) }
    val publisher = request.body match {
      case Some(json) => HttpRequest.BodyPublishers.ofString(Json.stringify(json))
      case None       => HttpRequest.BodyPublishers.noBody()
    }
    builder.method(request.method.toUpperCase, publisher)
    val response = client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray())
    HttpResult(
      response.statusCode(),
      response.body(),
      response.headers().map().asScala.view.mapValues(_.asScala.toSeq).toMap
    )
  }
}

final class CrowdSecContainer(image: String) extends GenericContainer[CrowdSecContainer](DockerImageName.parse(image))

/**
 * Drives the real `CrowdSecClient` against a real CrowdSec Local API.
 *
 * The point of this suite is the half of `REP-4` that cannot be verified by reading: the exact
 * shapes the LAPI accepts and returns. Decision streaming, watcher login and the alert payload are
 * all contracts owned by CrowdSec, and a unit test asserting my own JSON back to me proves nothing
 * about them.
 *
 * Skips itself when no docker daemon is reachable, so `sbt test` stays runnable without one.
 */
class CrowdSecIntegrationSuite extends munit.FunSuite {

  CrowdSecIntegrationSuite.pinDockerApiVersion()

  private val image        = "crowdsecurity/crowdsec:v1.6.4"
  private val machineId    = "otoroshi-test-machine"
  private val machinePass  = "otoroshi-test-password"
  private val dockerThere  = try DockerClientFactory.instance().isDockerAvailable catch { case _: Throwable => false }

  override def munitIgnore: Boolean         = !dockerThere
  override val munitTimeout: FiniteDuration = 5.minutes

  private given ExecutionContext = ExecutionContext.global

  private var container: CrowdSecContainer = scala.compiletime.uninitialized
  private var bouncerKey: String           = ""
  private var lapiUrl: String              = ""
  private var registry: ReputationRegistry = scala.compiletime.uninitialized
  private var client: CrowdSecClient       = scala.compiletime.uninitialized

  override def beforeAll(): Unit = {
    if (dockerThere) {
      container = new CrowdSecContainer(image)
      container.withExposedPorts(8080)
      container.withEnv("DISABLE_ONLINE_API", "true")
      container.withEnv("DISABLE_AGENT", "true")
      container.withEnv("NO_HUB_UPGRADE", "true")
      container.setWaitStrategy(Wait.forHttp("/health").forPort(8080).forStatusCode(200).withStartupTimeout(JDuration.ofMinutes(3)))
      container.start()

      lapiUrl = s"http://${container.getHost}:${container.getMappedPort(8080)}"
      bouncerKey = cscli("bouncers", "add", "otoroshi-test-bouncer", "-o", "raw")
      // "-f -" prints the credentials instead of writing the node's own local_api_credentials.yaml,
      // which already exists in the image and would make the command refuse to run
      cscli("machines", "add", machineId, "--password", machinePass, "-f", "-")

      registry = new ReputationRegistry()
      client = new CrowdSecClient(new JdkHttpClient(), registry, Logger("crowdsec-it"))
    }
  }

  override def afterAll(): Unit = {
    if (dockerThere && container != null) container.stop()
  }

  // -----------------------------------------------------------------------------------------------
  // helpers
  // -----------------------------------------------------------------------------------------------

  private def cscli(args: String*): String = {
    val result = container.execInContainer(("cscli" +: args)*)
    if (result.getExitCode != 0) {
      fail(s"cscli ${args.mkString(" ")} failed (${result.getExitCode}):\n${result.getStderr}\n${result.getStdout}")
    }
    result.getStdout.trim
  }

  private def bouncer(
      id: String,
      push: Boolean = false,
      withDecision: Boolean = false,
      apiKey: String = bouncerKey,
      password: String = machinePass
  ): CrowdSecBouncer = CrowdSecBouncer(
    id = id,
    name = s"bouncer $id",
    lapiUrl = lapiUrl,
    apiKey = apiKey,
    pushEnabled = push,
    pushMachineId = machineId,
    pushPassword = password,
    pushScenario = "cloud-apim/otoroshi-integration-test",
    pushWithDecision = withDecision,
    pushDecisionDuration = "1h",
    pushMaxBatch = 10
  )

  private def await[A](f: Future[A]): A = Await.result(f, 60.seconds)

  private def decisionsJson: JsValue = {
    val raw = cscli("decisions", "list", "-o", "json")
    if (raw.isEmpty || raw == "null") JsArray(Seq.empty) else Json.parse(raw)
  }

  private def alertsJson: JsValue = {
    val raw = cscli("alerts", "list", "-o", "json")
    if (raw.isEmpty || raw == "null") JsArray(Seq.empty) else Json.parse(raw)
  }

  private def decisionValues: Seq[String] =
    decisionsJson.asOpt[JsArray].map(_.value.toSeq).getOrElse(Seq.empty).flatMap { alert =>
      (alert \ "decisions").asOpt[JsArray].map(_.value.toSeq).getOrElse(Seq.empty).flatMap { d =>
        (d \ "value").asOpt[String]
      }
    }

  /** The LAPI is eventually consistent enough that a fresh write needs a beat before it streams. */
  private def eventually(what: String)(check: => Boolean): Unit = {
    val deadline = System.currentTimeMillis() + 20000L
    var ok       = check
    while (!ok && System.currentTimeMillis() < deadline) {
      Thread.sleep(500L)
      ok = check
    }
    if (!ok) fail(s"timed out waiting for: $what")
  }

  // -----------------------------------------------------------------------------------------------
  // pull
  // -----------------------------------------------------------------------------------------------

  test("the startup sync pulls existing decisions from a real lapi") {
    cscli("decisions", "add", "--ip", "203.0.113.10", "--duration", "4h", "--reason", "otoroshi-test")
    val b = bouncer("pull-1")
    eventually("the decision to reach the stream") {
      await(client.pull(b)).isRight && registry.crowdSecStore("pull-1").lookup("203.0.113.10").isDefined
    }
    val store = registry.crowdSecStore("pull-1")
    assert(store.initialized, "the store should be marked as initialised after a startup sync")
    assertEquals(store.lastError, None)
    val decision = store.lookup("203.0.113.10")
    assert(decision.isDefined)
    assertEquals(decision.get.typ, "ban")
    assertEquals(decision.get.scope.toLowerCase, "ip")
    assert(store.lookup("203.0.113.11").isEmpty, "an unrelated address must not match")
  }

  test("range scoped decisions are honoured") {
    cscli("decisions", "add", "--range", "198.51.100.0/24", "--duration", "4h", "--reason", "otoroshi-test-range")
    val b = bouncer("pull-2")
    eventually("the range decision to reach the stream") {
      await(client.pull(b)).isRight && registry.crowdSecStore("pull-2").lookup("198.51.100.42").isDefined
    }
    val store = registry.crowdSecStore("pull-2")
    assert(store.lookup("198.51.100.1").isDefined)
    assert(store.lookup("198.51.100.255").isDefined)
    assert(store.lookup("198.51.101.1").isEmpty, "an address outside the range must not match")
  }

  test("a deleted decision disappears on the next delta") {
    cscli("decisions", "add", "--ip", "203.0.113.77", "--duration", "4h", "--reason", "otoroshi-test-delete")
    val b = bouncer("pull-3")
    eventually("the decision to appear")(
      await(client.pull(b)).isRight && registry.crowdSecStore("pull-3").lookup("203.0.113.77").isDefined
    )
    cscli("decisions", "delete", "--ip", "203.0.113.77")
    eventually("the deletion to reach the stream")(
      await(client.pull(b)).isRight && registry.crowdSecStore("pull-3").lookup("203.0.113.77").isEmpty
    )
    assert(registry.crowdSecStore("pull-3").initialized, "a delta must not reset the store")
  }

  test("a rejected api key is reported and leaves the mirror intact") {
    val good = bouncer("pull-4")
    eventually("a first successful sync")(await(client.pull(good)).isRight)
    val held = registry.crowdSecStore("pull-4").size
    assert(held > 0, "the mirror should hold something before we break the key")

    val broken = bouncer("pull-4", apiKey = "definitely-not-a-valid-key")
    val result = await(client.pull(broken))
    assert(result.isLeft, s"a bad api key must fail, got $result")
    val store  = registry.crowdSecStore("pull-4")
    assert(store.lastError.isDefined, "the failure must be visible")
    assertEquals(store.size, held, "a failed sync must not empty the mirror")
  }

  // -----------------------------------------------------------------------------------------------
  // push
  // -----------------------------------------------------------------------------------------------

  test("an alert is accepted by the lapi and creates no decision on its own") {
    val b = bouncer("push-1", push = true)
    client.enqueue(b, CrowdSecSignal("203.0.113.201", "otoroshi waf blocked a request (rules 942100)"))
    assertEquals(client.pendingPushes("push-1"), 1)

    val pushed = await(client.flush(b))
    assertEquals(pushed, Right(1), s"the lapi rejected the alert payload: $pushed")
    assertEquals(client.pendingPushes("push-1"), 0)

    eventually("the alert to be stored") {
      alertsJson.asOpt[JsArray].exists(_.value.exists { alert =>
        (alert \ "scenario").asOpt[String].contains("cloud-apim/otoroshi-integration-test") &&
        (alert \ "source" \ "value").asOpt[String].contains("203.0.113.201")
      })
    }
    assert(
      !decisionValues.contains("203.0.113.201"),
      "a signal-only alert must not ban anything by itself"
    )
  }

  test("an alert carrying a decision does ban the address") {
    val b = bouncer("push-2", push = true, withDecision = true)
    client.enqueue(b, CrowdSecSignal("203.0.113.202", "otoroshi waf blocked a request (rules 942100)"))
    assertEquals(await(client.flush(b)), Right(1))
    eventually("the ban to be created")(decisionValues.contains("203.0.113.202"))
  }

  test("a decision otoroshi pushed comes back through the bouncer stream") {
    val push = bouncer("push-3", push = true, withDecision = true)
    client.enqueue(push, CrowdSecSignal("203.0.113.203", "otoroshi waf blocked a request"))
    assertEquals(await(client.flush(push)), Right(1))

    val pull = bouncer("roundtrip")
    eventually("the pushed ban to come back on the stream") {
      await(client.pull(pull)).isRight && registry.crowdSecStore("roundtrip").lookup("203.0.113.203").isDefined
    }
    val decision = registry.crowdSecStore("roundtrip").lookup("203.0.113.203").get
    assertEquals(decision.scenario, "cloud-apim/otoroshi-integration-test")
  }

  test("several signals for one address collapse into a single alert") {
    val b = bouncer("push-4", push = true)
    (1 to 3).foreach(i => client.enqueue(b, CrowdSecSignal("203.0.113.204", s"detection $i")))
    assertEquals(client.pendingPushes("push-4"), 3)
    assertEquals(await(client.flush(b)), Right(1), "three signals for one ip should be one alert")

    eventually("the alert to be stored") {
      alertsJson.asOpt[JsArray].exists(_.value.exists { alert =>
        (alert \ "source" \ "value").asOpt[String].contains("203.0.113.204") &&
        (alert \ "events_count").asOpt[Int].contains(3)
      })
    }
  }

  test("a rejected machine password is reported and the batch is not silently lost twice") {
    val b = bouncer("push-5", push = true, password = "wrong-password")
    client.enqueue(b, CrowdSecSignal("203.0.113.205", "detection"))
    val result = await(client.flush(b))
    assert(result.isLeft, s"a bad machine password must fail, got $result")
    assert(result.left.exists(_.toLowerCase.contains("login")), s"the error should name the login step: $result")
    assert(!decisionValues.contains("203.0.113.205"))
  }

  test("flushing an empty queue is a no-op, not a call") {
    assertEquals(await(client.flush(bouncer("push-6", push = true))), Right(0))
  }
}

object CrowdSecIntegrationSuite {

  /** Kept as the suite's own entry point; the reasoning lives in [[DockerSupport]]. */
  def pinDockerApiVersion(): Unit = com.cloud.apim.otoroshi.extensions.waf.DockerSupport.pinApiVersion()
}
