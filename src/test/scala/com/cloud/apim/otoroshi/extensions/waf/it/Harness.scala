package com.cloud.apim.otoroshi.extensions.waf.it

import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.model.*
import org.apache.pekko.stream.Materializer
import org.apache.pekko.util.ByteString
import otoroshi.api.Otoroshi
import otoroshi.models.{EntityLocation, RoundRobin}
import otoroshi.next.models.*
import play.api.libs.json.{JsValue, Json}
import play.api.libs.ws.{WSAuthScheme, WSClient, WSResponse}
import play.api.libs.ws.DefaultBodyWritables.{writeableOf_String, writeableOf_ByteArray}
import play.core.server.ServerConfig

import java.net.ServerSocket
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicLong
import scala.concurrent.duration.*
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.Try

/**
 * A real Otoroshi, in this process, with the extension on the classpath.
 *
 * Otoroshi's own functional harness lives in its test sources and is not published, and `utils.scala`
 * is nearly three thousand lines of things this repository does not need. Booting the gateway,
 * however, is a public API of the published jar — so what is copied here is the shape of
 * `startOtoroshi`, not the harness itself.
 *
 * One instance is shared by every integration suite: a boot costs about fifteen seconds, which is
 * worth paying once and not once per suite.
 */
object Gateway {

  private def freePort: Int = {
    val socket = new ServerSocket(0)
    val port   = socket.getLocalPort
    socket.close()
    port
  }

  lazy val port: Int = freePort

  lazy val instance: Otoroshi = {
    val config = ConfigFactory
      .parseString(s"""
        |otoroshi.storage = "inmemory"
        |otoroshi.next.state-sync-interval = 5
        |otoroshi.admin-extensions.configurations.cloud-apim_extensions_waf.enabled = true
        |""".stripMargin)
      .resolve()
    val oto    = Otoroshi(
      ServerConfig(
        address = "0.0.0.0",
        port = Some(port),
        rootDir = Files.createTempDirectory("waf-extension-it").toFile
      ),
      config
    )
    oto.startAndStopOnShutdown()
    waitUntilHealthy(oto)
    oto
  }

  given system: ActorSystem      = instance.system
  given mat: Materializer        = instance.materializer
  given ec: ExecutionContext     = instance.executionContext
  def ws: WSClient               = instance.ws

  private def waitUntilHealthy(oto: Otoroshi): Unit = {
    val deadline = System.currentTimeMillis() + 120000L
    var healthy  = false
    while (!healthy && System.currentTimeMillis() < deadline) {
      healthy = Try(
        Await.result(oto.ws.url(s"http://127.0.0.1:$port/health").withRequestTimeout(2.seconds).get(), 5.seconds).status
      ).toOption.contains(200)
      if (!healthy) Thread.sleep(500L)
    }
    if (!healthy) throw new RuntimeException(s"otoroshi did not become healthy on port $port")
    // the router syncs on a tick; a route created before it has run would not be matched yet
    Thread.sleep(2000L)
  }

  // -----------------------------------------------------------------------------------------------
  // admin api
  // -----------------------------------------------------------------------------------------------

  def admin(path: String): play.api.libs.ws.WSRequest =
    ws.url(s"http://127.0.0.1:$port$path")
      .withHttpHeaders("Host" -> "otoroshi-api.oto.tools", "Content-Type" -> "application/json")
      .withAuth("admin-api-apikey-id", "admin-api-apikey-secret", WSAuthScheme.BASIC)

  def post(path: String, body: JsValue): WSResponse = await(admin(path).post(Json.stringify(body)))
  def delete(path: String): WSResponse              = await(admin(path).delete())

  def createWafConfig(body: JsValue): String = {
    val res = post("/apis/waf.extensions.cloud-apim.com/v1/waf-configs", body)
    if (res.status > 299) throw new RuntimeException(s"could not create the waf config: ${res.status} ${res.body}")
    (res.json \ "id").as[String]
  }

  def deleteWafConfig(id: String): Unit = { delete(s"/apis/waf.extensions.cloud-apim.com/v1/waf-configs/$id"); () }

  def createWafRuleset(body: JsValue): String = {
    val res = post("/apis/waf.extensions.cloud-apim.com/v1/waf-rulesets", body)
    if (res.status > 299) throw new RuntimeException(s"could not create the ruleset: ${res.status} ${res.body}")
    (res.json \ "id").as[String]
  }

  def deleteWafRuleset(id: String): Unit = {
    delete(s"/apis/waf.extensions.cloud-apim.com/v1/waf-rulesets/$id")
    ()
  }

  /** A route on `<id>.oto.tools`, pointed at a local backend, carrying the given plugins. */
  def createRoute(id: String, backendPort: Int, plugins: Seq[NgPluginInstance]): NgRoute = {
    val domain = s"$id.oto.tools"
    val route  = NgRoute(
      location = EntityLocation.default,
      id = s"route_$id",
      name = id,
      description = id,
      enabled = true,
      debugFlow = false,
      capture = false,
      exportReporting = false,
      frontend = NgFrontend(
        domains = Seq(NgDomainAndPath(domain)),
        headers = Map.empty,
        cookies = Map.empty,
        query = Map.empty,
        methods = Seq.empty,
        stripPath = true,
        exact = false
      ),
      backend = NgBackend(
        targets = Seq(NgTarget(hostname = "127.0.0.1", port = backendPort, id = "local.target", tls = false)),
        root = "/",
        rewrite = false,
        loadBalancing = RoundRobin,
        client = NgClientConfig.default
      ),
      plugins = NgPlugins(plugins),
      tags = Seq.empty,
      metadata = Map.empty
    )
    val res = post("/api/routes", route.json)
    if (res.status > 299) throw new RuntimeException(s"could not create the route: ${res.status} ${res.body}")
    // the proxy state is refreshed on a tick rather than on write
    Thread.sleep(1500L)
    route
  }

  def deleteRoute(route: NgRoute): Unit = { delete(s"/api/routes/${route.id}"); Thread.sleep(500L) }

  // -----------------------------------------------------------------------------------------------
  // calling through the gateway
  // -----------------------------------------------------------------------------------------------

  def call(
      route: NgRoute,
      path: String = "/",
      method: String = "GET",
      body: Option[ByteString] = None,
      contentType: String = "application/octet-stream"
  ): WSResponse = {
    val req = ws
      .url(s"http://127.0.0.1:$port$path")
      .withHttpHeaders("Host" -> route.frontend.domains.head.domain)
      .withRequestTimeout(120.seconds)
      .withMethod(method)
    await(body match {
      case None    => req.execute()
      case Some(b) => req.addHttpHeaders("Content-Type" -> contentType).withBody(b.toArray).execute()
    })
  }

  def await[A](f: Future[A]): A = Await.result(f, 120.seconds)
}

/**
 * A backend on a free port.
 *
 * It counts what it actually received, which is how the integration tests tell "the WAF forwarded
 * the whole body" from "the WAF forwarded what it had inspected".
 */
class TestBackend(
    status: Int = 200,
    contentType: String = "application/json",
    responseBody: ByteString = ByteString("""{"ok":true}""")
)(using system: ActorSystem, mat: Materializer, ec: ExecutionContext) {

  private val mediaType: ContentType =
    ContentType.parse(contentType).getOrElse(ContentTypes.`application/json`)

  val received: AtomicLong = new AtomicLong(0L)
  val calls: AtomicLong    = new AtomicLong(0L)

  private val binding = Await.result(
    Http()
      .newServerAt("127.0.0.1", 0)
      .bind { request =>
        calls.incrementAndGet()
        request.entity.dataBytes
          .runFold(0L)((acc, bs) => acc + bs.size)
          .map { size =>
            received.addAndGet(size)
            HttpResponse(
              status = StatusCodes.getForKey(status).getOrElse(StatusCodes.OK),
              entity = HttpEntity(mediaType, responseBody)
            )
          }
      },
    30.seconds
  )

  def port: Int   = binding.localAddress.getPort
  def stop(): Unit = { Await.result(binding.unbind(), 10.seconds); () }
}
