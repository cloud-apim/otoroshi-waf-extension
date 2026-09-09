package otoroshi_plugins.com.cloud.apim.otoroshi.extensions.waf.plugins

import com.cloud.apim.otoroshi.extensions.waf.security.{ClientIdentity, IdentityRef}
import play.api.libs.json.Json

import scala.concurrent.duration.*

class Fail2BanConfigSuite extends munit.FunSuite {

  private val default = CloudApimFail2BanConfig.default

  test("the default status set excludes 404 and 5xx — you would be banning your own users") {
    assertEquals(default.isFailure(401), true)
    assertEquals(default.isFailure(403), true)
    assertEquals(default.isFailure(429), true)
    assertEquals(default.isFailure(404), false, "a broken link is not an attack")
    assertEquals(default.isFailure(500), false, "your outage is not the caller's fault")
    assertEquals(default.isFailure(200), false)
  }

  test("it starts observing, not banning") {
    assertEquals(default.dryRun, true)
  }

  test("status ranges parse both notations, inclusively") {
    assertEquals(StatusRange.parse("403"), Some(StatusRange(403, 403)))
    assertEquals(StatusRange.parse(" 500-599 "), Some(StatusRange(500, 599)))
    assertEquals(StatusRange.parse("599-500"), None, "an inverted range is a typo, not a range")
    assertEquals(StatusRange.parse("nope"), None)
    assert(StatusRange(500, 599).contains(500) && StatusRange(500, 599).contains(599))
  }

  test("an unparseable status list falls back to the defaults rather than counting nothing") {
    val cfg = CloudApimFail2BanConfig.format.reads(Json.obj("status_codes" -> Json.arr("nope"))).get
    assertEquals(cfg.statusCodes, CloudApimFail2BanConfig.defaultStatuses)
  }

  test("durations read the suffixed forms and plain millis") {
    def read(v: String) = CloudApimFail2BanConfig.format.reads(Json.obj("ban_time" -> v)).get.banTime
    assertEquals(read("90s"), 90.seconds)
    assertEquals(read("15m"), 15.minutes)
    assertEquals(read("2h"), 2.hours)
    assertEquals(read("1d"), 1.day)
    assertEquals(CloudApimFail2BanConfig.format.reads(Json.obj("ban_time" -> 4500)).get.banTime, 4500.millis)
    assertEquals(read("garbage"), default.banTime, "a bad duration keeps the default, it does not become zero")
  }

  test("path rules default to counting everything, and 'block' takes a path out") {
    assertEquals(default.isInScope("/anything"), true)
    val scoped = default.copy(urlRules = Seq(UrlRule("/health", "block")))
    assertEquals(scoped.isInScope("/health"), false)
    assertEquals(scoped.isInScope("/login"), true)
  }

  test("ignored accepts a wildcard, an Ip(...) and a Cidr(...)") {
    val cfg = default.copy(ignored = Seq("Cidr(10.0.0.0/8)", "Ip(192.168.1.1)", "*-probe"))
    // upstream never strips the Cidr(...) wrapper before parsing, so this case matches nothing there
    assertEquals(cfg.isIgnored("10.4.2.1"), true)
    assertEquals(cfg.isIgnored("192.168.1.1"), true)
    assertEquals(cfg.isIgnored("uptime-probe"), true, "the bare form is a wildcard, not a regex")
    assertEquals(cfg.isIgnored("8.8.8.8"), false)
  }

  test("'auto' bans the most specific identity, 'ip' always the address") {
    val identity = ClientIdentity(ip = "1.2.3.4", apikey = Some("k1"))
    assertEquals(default.refOf(identity), IdentityRef("apikey", "k1"))
    assertEquals(default.copy(banScope = "ip").refOf(identity), IdentityRef("ip", "1.2.3.4"))
  }

  test("an anonymous caller falls back to the address whatever the scope") {
    val identity = ClientIdentity(ip = "1.2.3.4")
    assertEquals(default.refOf(identity), IdentityRef("ip", "1.2.3.4"))
  }

  test("the counter key defaults to per-route, so one route's failures do not ban on another") {
    assertEquals(default.counterKey, "${route.id}-${req.ip}")
  }

  test("an empty config yields the defaults") {
    assertEquals(CloudApimFail2BanConfig.format.reads(Json.obj()).get, default)
  }

  test("the config round-trips") {
    val cfg  = CloudApimFail2BanConfig(
      counterKey = "${req.ip}",
      banScope = "ip",
      detectTime = 5.minutes,
      banTime = 30.minutes,
      maxRetry = 9,
      statusCodes = Seq(StatusRange(401, 401), StatusRange(500, 599)),
      urlRules = Seq(UrlRule("/api/.*", "allow")),
      ignored = Seq("Cidr(10.0.0.0/8)"),
      dryRun = false,
      fabricWeight = 12
    )
    assertEquals(CloudApimFail2BanConfig.format.reads(cfg.json).get, cfg)
  }

  test("every field of the flow is described by the schema") {
    val described = CloudApimFail2BanConfig.configSchema.keys
    assertEquals(CloudApimFail2BanConfig.configFlow.filterNot(described.contains), Seq.empty[String])
  }
}
