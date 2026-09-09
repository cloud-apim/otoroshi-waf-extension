package otoroshi_plugins.com.cloud.apim.otoroshi.extensions.waf

import com.cloud.apim.otoroshi.extensions.waf.entities.*
import com.cloud.apim.otoroshi.extensions.waf.reputation.ReputationModule
import com.cloud.apim.otoroshi.extensions.waf.security.SecurityModule
import com.cloud.apim.seclang.impl.utils.StatusCodes
import com.cloud.apim.seclang.model.*
import com.cloud.apim.seclang.scaladsl.SecLang
import com.cloud.apim.seclang.scaladsl.coreruleset.EmbeddedCRSPreset
import com.github.blemale.scaffeine.Scaffeine
import org.apache.pekko.stream.Materializer
import org.apache.pekko.stream.scaladsl.{Source, StreamConverters}
import org.apache.pekko.util.ByteString
import org.joda.time.DateTime
import otoroshi.env.Env
import otoroshi.events.AnalyticEvent
import otoroshi.models.*
import otoroshi.next.extensions.*
import otoroshi.security.IdGenerator
import otoroshi.utils.cache.types.UnboundedTrieMap
import otoroshi.utils.syntax.implicits.*
import play.api.libs.json.{JsObject, JsValue, Json}
import play.api.mvc.{RequestHeader, Result, Results}
import play.api.{Configuration, Logger}

import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

class WafExtensionDatastores(env: Env, extensionId: AdminExtensionId) {
  val wafConfigDatastore: CloudApimWafConfigDatastore = new KvCloudApimWafConfigDatastore(extensionId, env.datastores.redis, env)
  val wafRulesetDatastore: WafRulesetDatastore        = new KvWafRulesetDatastore(extensionId, env.datastores.redis, env)
}

class WafExtensionState() {

  private val logger = Logger("cloud-apim-waf-state")

  private val _configs  = new UnboundedTrieMap[String, CloudApimWafConfig]()
  private val _rulesets = new UnboundedTrieMap[String, WafRuleset]()
  // composed once per change rather than once per request: resolving references on the hot path
  // would put a map walk in front of every single call
  private val _composed = new UnboundedTrieMap[String, ComposedRules]()

  def config(id: String): Option[CloudApimWafConfig] = _configs.get(id)
  def allConfigs(): Seq[CloudApimWafConfig]          = _configs.values.toSeq
  def ruleset(id: String): Option[WafRuleset]        = _rulesets.get(id)
  def allRulesets(): Seq[WafRuleset]                 = _rulesets.values.toSeq

  def updateConfigs(values: Seq[CloudApimWafConfig]): Unit = {
    _configs.addAll(values.map(v => (v.id, v))).remAll(_configs.keySet.toSeq.diff(values.map(_.id)))
    recompose()
  }

  def updateRulesets(values: Seq[WafRuleset]): Unit = {
    _rulesets.addAll(values.map(v => (v.id, v))).remAll(_rulesets.keySet.toSeq.diff(values.map(_.id)))
    recompose()
  }

  /** The rules a config actually runs — its rulesets in order, then its own inline rules. */
  def composedFor(config: CloudApimWafConfig): ComposedRules =
    _composed.getOrElse(config.id, WafRuleComposition.compose(config, ruleset))

  def rulesFor(config: CloudApimWafConfig): Seq[String] = composedFor(config).rules

  private def recompose(): Unit = {
    _composed.remAll(_composed.keySet.toSeq.diff(_configs.keySet.toSeq))
    _configs.values.foreach { config =>
      val composed = WafRuleComposition.compose(config, ruleset)
      // a reference to nothing still compiles and still runs, protecting less than it claims to,
      // so it gets said out loud rather than swallowed
      if (composed.missing.nonEmpty) {
        logger.warn(s"waf config '${config.name}' references unknown rulesets: ${composed.missing.mkString(", ")}")
      }
      _composed.put(config.id, composed)
    }
  }
}

class CloudApimWafIntegration(env: Env, configuration: Configuration) extends SecLangIntegration {

  private val logger = Logger("cloud-apim-waf")
  private val maxCacheItems = configuration.getOptional[Int]("integration.max-cache-items").getOrElse(1000)
  private val log = configuration.getOptional[Boolean]("integration.log").getOrElse(true)

  private val cache = Scaffeine()
    .expireAfter[String, (CompiledProgram, FiniteDuration)](
      create = (_, value) => value._2,
      update = (_, _, currentDuration) => currentDuration,
      read = (_, _, currentDuration) => currentDuration
    )
    .maximumSize(maxCacheItems)
    .build[String, (CompiledProgram, FiniteDuration)]()

  override def logDebug(msg: String): Unit = if (log && logger.isDebugEnabled) logger.debug(msg)
  override def logInfo(msg: String): Unit = if (log && logger.isInfoEnabled) logger.info(msg)
  override def logAudit(msg: String): Unit = ()
  override def logError(msg: String): Unit = if (log && logger.isErrorEnabled) logger.error(msg)

  override def getEnv: Map[String, String] = sys.env

  override def getExternalPreset(name: String): Option[SecLangPreset] = None
  override def getCachedProgram(key: String): Option[CompiledProgram] = cache.getIfPresent(key).map(_._1)
  override def putCachedProgram(key: String, program: CompiledProgram, ttl: FiniteDuration): Unit = cache.put(key, (program, ttl))
  override def removeCachedProgram(key: String): Unit = cache.invalidate(key)

  override def audit(ruleId: Int, context: RequestContext, state: RuntimeState, phase: Int, msg: String, logdata: List[String]): Unit = {
    CloudApimWafAuditEvent(ruleId, context, state, phase, msg, logdata).toAnalytics()(using env)
  }
}

class CloudApimWafExtension(val env: Env) extends AdminExtension {

  private lazy val datastores = new WafExtensionDatastores(env, id)
  lazy val states = new WafExtensionState()
  // ip reputation lives in its own module so the waf entities, plugins and storage keys stay untouched
  lazy val reputation = new ReputationModule(env, id, configuration)
  // the decision fabric every detector contributes to, and the one thing that acts on it
  lazy val security = new SecurityModule(env, id, configuration)
  private val logger = Logger("cloud-apim-waf-extension")
  private val presets: Map[String, SecLangPreset] = Map("crs" -> EmbeddedCRSPreset.embedded)
  private val config = SecLangEngineConfig.default
  private val integration = new CloudApimWafIntegration(env, configuration)

  val factory = SecLang.factory(presets, config, integration)

  override def id: AdminExtensionId = AdminExtensionId("cloud-apim.extensions.Waf")
  override def name: String = "Cloud APIM - Security Suite"
  override def description: Option[String] = "A security suite for Otoroshi: a JVM implementation of a WAF with ModSecurity SecLang support and the OWASP CRS, plus ip reputation from threat intelligence feeds and CrowdSec".some
  override def enabled: Boolean = env.isDev || configuration.getOptional[Boolean]("enabled").getOrElse(false)

  override def start(): Unit = {
    logger.info("the 'Cloud APIM - Security Suite' extension is enabled !")
    reputation.start()
    security.start()
  }

  override def stop(): Unit = {
    reputation.stop()
    security.stop()
  }

  override def frontendExtensions(): Seq[AdminExtensionFrontendExtension] = Seq(
    AdminExtensionFrontendExtension(
      path = "/extensions/assets/cloud-apim/extensions/waf/extension.js"
    )
  )

  override def syncStates(): Future[Unit] = {
    given ExecutionContext = env.otoroshiExecutionContext
    given Env              = env
    for {
      rulesets <- datastores.wafRulesetDatastore.findAllAndFillSecrets()
      configs  <- datastores.wafConfigDatastore.findAllAndFillSecrets()
      _        <- reputation.syncStates()
      _        <- security.syncStates()
    } yield {
      // rulesets first: composing a config against a stale ruleset map would be wrong for one tick
      states.updateRulesets(rulesets)
      states.updateConfigs(configs)
      com.cloud.apim.otoroshi.extensions.waf.analytics.SecurityDashboard.seedIfMissing()
      ()
    }
  }

  override def analyticsQueries(): Seq[otoroshi.next.analytics.queries.AnalyticsQuery] =
    com.cloud.apim.otoroshi.extensions.waf.analytics.SecurityQueries.all

  // without this the suite's events reach whatever log pipeline the operator built and never a
  // table anyone can query, which is what left `analyticsQueries()` with nothing to read
  override def analyticsProjections(): Seq[otoroshi.next.analytics.exporter.AnalyticsProjection] =
    Seq(
      com.cloud.apim.otoroshi.extensions.waf.analytics.CloudApimSecurityEventProjection,
      com.cloud.apim.otoroshi.extensions.waf.analytics.CloudApimWafTrailEventProjection
    )

  override def entities(): Seq[AdminExtensionEntity[EntityLocationSupport]] = {
    Seq(
      AdminExtensionEntity(CloudApimWafConfig.resource(env, datastores, states)),
      AdminExtensionEntity(WafRuleset.resource(env, datastores, states)),
    ) ++ reputation.entities() ++ security.entities()
  }

  def getResourceCode(path: String): String = {
    given ExecutionContext = env.otoroshiExecutionContext
    given Materializer     = env.otoroshiMaterializer
    env.environment.resourceAsStream(path)
      .map(stream => StreamConverters.fromInputStream(() => stream).runFold(ByteString.empty)(_++_).awaitf(10.seconds).utf8String)
      .getOrElse(s"'resource ${path} not found !'")
  }

  lazy val wafConfigsPageCode = getResourceCode("cloudapim/extensions/waf/WafConfigsPage.js")
  lazy val imgCode = getResourceCode("cloudapim/extensions/waf/icon.svg")
  lazy val reputationPagesCode = getResourceCode("cloudapim/extensions/waf/ReputationPages.js")
  lazy val reputationImgCode = getResourceCode("cloudapim/extensions/waf/reputation-icon.svg")
  lazy val securityPagesCode = getResourceCode("cloudapim/extensions/waf/SecurityPages.js")
  lazy val wafRulesetsPageCode = getResourceCode("cloudapim/extensions/waf/WafRulesetsPage.js")
  lazy val posturePageCode = getResourceCode("cloudapim/extensions/waf/PosturePage.js")

  override def assets(): Seq[AdminExtensionAssetRoute] = Seq(
    AdminExtensionAssetRoute(
      path = "/extensions/assets/cloud-apim/extensions/waf/icon.svg",
      handle = (_: AdminExtensionRouterContext[AdminExtensionAssetRoute], _: RequestHeader) => {
        Results.Ok(imgCode).as("image/svg+xml").vfuture
      }
    ),
    AdminExtensionAssetRoute(
      path = "/extensions/assets/cloud-apim/extensions/waf/reputation-icon.svg",
      handle = (_: AdminExtensionRouterContext[AdminExtensionAssetRoute], _: RequestHeader) => {
        Results.Ok(reputationImgCode).as("image/svg+xml").vfuture
      }
    ),
    AdminExtensionAssetRoute(
      path = "/extensions/assets/cloud-apim/extensions/waf/extension.js",
      handle = (_: AdminExtensionRouterContext[AdminExtensionAssetRoute], _: RequestHeader) => {
        Results.Ok(
          s"""(function() {
             |  const extensionId = "${id.value}";
             |  Otoroshi.registerExtension(extensionId, false, (ctx) => {
             |
             |    const dependencies = ctx.dependencies;
             |
             |    const React     = dependencies.react;
             |    const _         = dependencies.lodash;
             |    const Component = React.Component;
             |    const uuid      = dependencies.uuid;
             |    const Table     = dependencies.Components.Inputs.Table;
             |    const SelectInput = dependencies.Components.Inputs.SelectInput;
             |    const MonacoInput = dependencies.Components.Inputs.MonacoInput;
             |    const BackOfficeServices = dependencies.BackOfficeServices;
             |
             |    ${wafConfigsPageCode}
             |
             |    ${wafRulesetsPageCode}
             |
             |    ${reputationPagesCode}
             |
             |    ${securityPagesCode}
             |
             |    ${posturePageCode}
             |
             |    return {
             |      id: extensionId,
             |      categories:[{
             |        title: 'Security Suite',
             |        description: 'Web application firewall, ip reputation and threat intelligence for Otoroshi',
             |        features: [
             |          {
             |            title: 'WAF configs',
             |            description: 'ModSecurity SecLang rules and the OWASP Core Rule Set',
             |            absoluteImg: '/extensions/assets/cloud-apim/extensions/waf/icon.svg',
             |            link: '/extensions/cloud-apim/waf/wafconfigs',
             |            display: () => true,
             |            icon: () => 'fa-atom',
             |          },
             |          {
             |            title: 'WAF rulesets',
             |            description: 'Reusable bodies of SecLang, shared across configs',
             |            absoluteImg: '/extensions/assets/cloud-apim/extensions/waf/icon.svg',
             |            link: '/extensions/cloud-apim/waf/wafrulesets',
             |            display: () => true,
             |            icon: () => 'fa-layer-group',
             |          },
             |          ...ReputationFeatures,
             |          ...SecurityFeatures
             |        ]
             |      }],
             |      features: [
             |        {
             |          title: 'WAF configs',
             |          description: 'ModSecurity SecLang rules and the OWASP Core Rule Set',
             |          absoluteImg: '/extensions/assets/cloud-apim/extensions/waf/icon.svg',
             |          link: '/extensions/cloud-apim/waf/wafconfigs',
             |          display: () => true,
             |          icon: () => 'fa-atom',
             |        },
             |        {
             |          title: 'WAF rulesets',
             |          description: 'Reusable bodies of SecLang, shared across configs',
             |          absoluteImg: '/extensions/assets/cloud-apim/extensions/waf/icon.svg',
             |          link: '/extensions/cloud-apim/waf/wafrulesets',
             |          display: () => true,
             |          icon: () => 'fa-layer-group',
             |        },
             |        ...ReputationFeatures,
             |        ...SecurityFeatures
             |      ],
             |      sidebarItems: [
             |        {
             |          title: 'WAF configs',
             |          text: 'ModSecurity SecLang rules and the OWASP Core Rule Set',
             |          path: 'extensions/cloud-apim/waf/wafconfigs',
             |          icon: 'atom'
             |        },
             |        {
             |          title: 'WAF rulesets',
             |          text: 'Reusable bodies of SecLang, shared across configs',
             |          path: 'extensions/cloud-apim/waf/wafrulesets',
             |          icon: 'layer-group'
             |        },
             |        {
             |          title: 'Route posture',
             |          text: 'Which routes are protected, in which mode',
             |          path: 'extensions/cloud-apim/waf/posture',
             |          icon: 'clipboard-check'
             |        },
             |        ...ReputationSidebarItems,
             |        ...SecuritySidebarItems
             |      ],
             |      searchItems: [
             |        {
             |          action: () => {
             |            window.location.href = `/bo/dashboard/extensions/cloud-apim/waf/wafconfigs`
             |          },
             |          env: React.createElement('span', { className: "fas fa-atom" }, null),
             |          label: 'Cloud APIM Security Suite - WAF configs',
             |          value: 'wafconfigs',
             |        },
             |        {
             |          action: () => {
             |            window.location.href = `/bo/dashboard/extensions/cloud-apim/waf/posture`
             |          },
             |          env: React.createElement('span', { className: "fas fa-clipboard-check" }, null),
             |          label: 'Cloud APIM Security Suite - Route posture',
             |          value: 'posture',
             |        },
             |        {
             |          action: () => {
             |            window.location.href = `/bo/dashboard/extensions/cloud-apim/waf/wafrulesets`
             |          },
             |          env: React.createElement('span', { className: "fas fa-layer-group" }, null),
             |          label: 'Cloud APIM Security Suite - WAF rulesets',
             |          value: 'wafrulesets',
             |        },
             |        ...ReputationSearchItems,
             |        ...SecuritySearchItems
             |      ],
             |      routes: [
             |        {
             |          path: '/extensions/cloud-apim/waf/wafconfigs/:taction/:titem',
             |          component: (props) => {
             |            return React.createElement(WafConfigsPage, props, null)
             |          }
             |        },
             |        {
             |          path: '/extensions/cloud-apim/waf/wafconfigs/:taction',
             |          component: (props) => {
             |            return React.createElement(WafConfigsPage, props, null)
             |          }
             |        },
             |        {
             |          path: '/extensions/cloud-apim/waf/wafconfigs',
             |          component: (props) => {
             |            return React.createElement(WafConfigsPage, props, null)
             |          }
             |        },
             |        {
             |          path: '/extensions/cloud-apim/waf/wafrulesets/:taction/:titem',
             |          component: (props) => {
             |            return React.createElement(WafRulesetsPage, props, null)
             |          }
             |        },
             |        {
             |          path: '/extensions/cloud-apim/waf/wafrulesets/:taction',
             |          component: (props) => {
             |            return React.createElement(WafRulesetsPage, props, null)
             |          }
             |        },
             |        {
             |          path: '/extensions/cloud-apim/waf/wafrulesets',
             |          component: (props) => {
             |            return React.createElement(WafRulesetsPage, props, null)
             |          }
             |        },
             |        {
             |          path: '/extensions/cloud-apim/waf/posture',
             |          component: (props) => {
             |            return React.createElement(SecurityPosturePage, props, null)
             |          }
             |        },
             |        ...ReputationRoutes,
             |        ...SecurityRoutes
             |      ]
             |    }
             |  });
             |})();
             |""".stripMargin).as("application/javascript").vfuture
      }
    )
  )

  override def backofficeAuthRoutes(): Seq[AdminExtensionBackofficeAuthRoute] = Seq(
    AdminExtensionBackofficeAuthRoute(
      method = "POST",
      path = "/extensions/cloud-apim/extensions/waf/utils/_compile",
      wantsBody = true,
      handle = (_, _, _, body) => handleCompile(body)
    ),
    AdminExtensionBackofficeAuthRoute(
      method = "POST",
      path = "/extensions/cloud-apim/extensions/waf/utils/_test",
      wantsBody = true,
      handle = (_, _, _, body) => handleTest(body)
    ),
  ) ++ reputation.backofficeAuthRoutes() ++ security.backofficeAuthRoutes()

  def handleCompile(body: Option[Source[ByteString, ?]]): Future[Result] = {
    given ExecutionContext = env.otoroshiExecutionContext
    given Materializer     = env.otoroshiMaterializer
    (body match {
      case None => Results.Ok(Json.obj("done" -> false, "error" -> "no body")).vfuture
      case Some(bodySource) => bodySource.runFold(ByteString.empty)(_ ++ _).flatMap { bodyRaw =>
        val bodyJson = bodyRaw.utf8String.parseJson
        // compiling the config's own rules alone would validate something it does not run: what
        // reaches the engine is the referenced rulesets first, then the inline rules
        val refs     = bodyJson.select("rulesets").asOpt[Seq[String]].getOrElse(Seq.empty).filter(_.trim.nonEmpty)
        val resolved = refs.map(ref => (ref, states.ruleset(ref)))
        val missing  = resolved.collect { case (ref, None) => ref }
        val composed = resolved.collect { case (_, Some(rs)) if rs.enabled => rs.rules }.flatten ++
          bodyJson.select("rules").asOpt[List[String]].getOrElse(List.empty)
        val rules = composed.filterNot(_.trim.startsWith("@import_preset ")).mkString("\n\n")
        (SecLang.parse(rules) match {
          case Left(err) => Results.Ok(Json.obj("done" -> false, "error" -> err.msg))
          case Right(conf) => Try(SecLang.compile(conf)) match {
            case Failure(err) => Results.Ok(Json.obj("done" -> false, "error" -> err.getMessage))
            case Success(_) => Results.Ok(Json.obj("done" -> true, "missing_rulesets" -> missing))
          }
        }).vfuture
      }
    }).recover {
      case e: Throwable => {
        e.printStackTrace()
        Results.Ok(Json.obj("done" -> false, "error" -> e.getMessage))
      }
    }
  }

  def handleTest(body: Option[Source[ByteString, ?]]): Future[Result] = {
    given ExecutionContext = env.otoroshiExecutionContext
    given Materializer     = env.otoroshiMaterializer
    (body match {
      case None => Results.Ok(Json.obj("done" -> false, "error" -> "no body")).vfuture
      case Some(bodySource) => bodySource.runFold(ByteString.empty)(_ ++ _).flatMap { bodyRaw =>
        val bodyJson = bodyRaw.utf8String.parseJson
        val rules = bodyJson.select("rules").asOpt[List[String]].getOrElse(List.empty)
        val request = bodyJson.select("request").asOpt[JsObject].getOrElse(Json.obj())
        val status = request.select("status").asOptInt
        val statusTxt = status.flatMap(s => StatusCodes.get(s))
        val requestCtx = RequestContext(
          method = request.select("method").asOptString.getOrElse("GET"),
          uri = request.select("uri").asOptString.getOrElse("/"),
          headers = com.cloud.apim.seclang.model.Headers(request.select("headers").asOpt[Map[String, String]].map(_.view.mapValues(List(_)).toMap).getOrElse(Map.empty)),
          cookies = request.select("cookies").asOpt[Map[String, String]].map(_.view.mapValues(List(_)).toMap).getOrElse(Map.empty),
          query = request.select("query").asOpt[Map[String, String]].map(_.view.mapValues(List(_)).toMap).getOrElse(Map.empty),
          body = request.select("body").asOpt[String].map(s => com.cloud.apim.seclang.model.ByteString(s)),
          status = status,
          statusTxt = statusTxt,
          remoteAddr = "127.0.0.0",
          remotePort = 56136,
          protocol = request.select("protocol").asOptString.getOrElse("HTTP/1.1"),
        )
        println(requestCtx.json.prettify)
        val res = factory.engine(rules).evaluate(requestCtx, List(1, 2, 3, 4, 5))
        Results.Ok(Json.obj("done" -> true, "result" -> res.json)).vfuture
      }
    }).recover {
      case e: Throwable => {
        e.printStackTrace()
        Results.Ok(Json.obj("done" -> false, "error" -> e.getMessage))
      }
    }
  }
}


case class CloudApimWafAuditEvent(ruleId: Int, context: RequestContext, state: RuntimeState, phase: Int, msg: String, logdata: List[String]) extends AnalyticEvent {

  override def `@service`: String            = "--"
  override def `@serviceId`: String          = "--"
  def `@id`: String                          = IdGenerator.uuid
  def `@timestamp`: org.joda.time.DateTime   = timestamp
  def `@type`: String                        = "CloudApimWafAuditEvent"
  override def fromOrigin: Option[String]    = None
  override def fromUserAgent: Option[String] = None

  private val timestamp = DateTime.now()

  override def toJson(using _env: Env): JsValue = {
    Json.obj(
      "@id"        -> `@id`,
      "@timestamp" -> play.api.libs.json.JodaWrites.JodaDateTimeNumberWrites.writes(timestamp),
      "@type"      -> "CloudApimWafAuditEvent",
      "@product"   -> "otoroshi",
      "@serviceId" -> `@serviceId`,
      "@service"   -> `@service`,
      "@env"       -> "prod",
      "rule_id"     -> ruleId,
      "phase"     -> phase,
      "msg"     -> msg,
      "logdata"     -> logdata,
    )
  }
}
