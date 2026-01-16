package otoroshi_plugins.com.cloud.apim.otoroshi.extensions.waf

import akka.stream.scaladsl.{Source, StreamConverters}
import akka.util.ByteString
import com.cloud.apim.otoroshi.extensions.waf.entities._
import com.cloud.apim.seclang.impl.utils.StatusCodes
import com.cloud.apim.seclang.model._
import com.cloud.apim.seclang.scaladsl.SecLang
import com.cloud.apim.seclang.scaladsl.coreruleset.EmbeddedCRSPreset
import com.github.blemale.scaffeine.Scaffeine
import org.joda.time.DateTime
import otoroshi.env.Env
import otoroshi.events.AnalyticEvent
import otoroshi.models._
import otoroshi.next.extensions._
import otoroshi.security.IdGenerator
import otoroshi.utils.cache.types.UnboundedTrieMap
import otoroshi.utils.syntax.implicits._
import play.api.libs.json.{JsObject, JsValue, Json}
import play.api.mvc.{RequestHeader, Result, Results}
import play.api.{Configuration, Logger}

import scala.concurrent.Future
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.util.{Failure, Success, Try}

class WafExtensionDatastores(env: Env, extensionId: AdminExtensionId) {
  val wafConfigDatastore: CloudApimWafConfigDatastore = new KvCloudApimWafConfigDatastore(extensionId, env.datastores.redis, env)
}

class WafExtensionState(env: Env) {

  private val _configs = new UnboundedTrieMap[String, CloudApimWafConfig]()
  def config(id: String): Option[CloudApimWafConfig] = _configs.get(id)
  def allConfigs(): Seq[CloudApimWafConfig]          = _configs.values.toSeq
  def updateConfigs(values: Seq[CloudApimWafConfig]): Unit = {
    _configs.addAll(values.map(v => (v.id, v))).remAll(_configs.keySet.toSeq.diff(values.map(_.id)))
  }
}

class CloudApimWafIntegration(env: Env, configuration: Configuration) extends SecLangIntegration {

  private val logger = Logger("cloud-apim-waf")
  private val maxCacheItems = configuration.getOptional[Int]("integration.max-cache-items").getOrElse(1000)
  private val log = configuration.getOptional[Boolean]("integration.log").getOrElse(true)

  private val cache = Scaffeine()
    .expireAfter[String, (CompiledProgram, FiniteDuration)](
      create = (key, value) => value._2,
      update = (key, value, currentDuration) => currentDuration,
      read = (key, value, currentDuration) => currentDuration
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
    CloudApimWafAuditEvent(ruleId, context, state, phase, msg, logdata).toAnalytics()(env)
  }
}

class CloudApimWafExtension(val env: Env) extends AdminExtension {

  private lazy val datastores = new WafExtensionDatastores(env, id)
  lazy val states = new WafExtensionState(env)
  private val logger = Logger("cloud-apim-waf-extension")
  private val presets = Map("crs" -> EmbeddedCRSPreset.embedded)
  private val config = SecLangEngineConfig.default
  private val integration = new CloudApimWafIntegration(env, configuration)

  val factory = SecLang.factory(presets, config, integration)

  override def id: AdminExtensionId = AdminExtensionId("cloud-apim.extensions.Waf")
  override def name: String = "WAF Extension"
  override def description: Option[String] = "This extensions provides a JVM implementation of a WAF providing ModSecurity Seclang support and including the CRS for Otoroshi".some
  override def enabled: Boolean = env.isDev || configuration.getOptional[Boolean]("enabled").getOrElse(false)

  override def start(): Unit = {
    logger.info("the 'WAF Extension' is enabled !")
  }

  override def stop(): Unit = {
  }

  override def frontendExtensions(): Seq[AdminExtensionFrontendExtension] = Seq(
    AdminExtensionFrontendExtension(
      path = "/extensions/assets/cloud-apim/extensions/waf/extension.js"
    )
  )

  override def syncStates(): Future[Unit] = {
    implicit val ec = env.otoroshiExecutionContext
    implicit val ev = env
    for {
      configs <- datastores.wafConfigDatastore.findAllAndFillSecrets()
    } yield {
      states.updateConfigs(configs)
      ()
    }
  }

  override def entities(): Seq[AdminExtensionEntity[EntityLocationSupport]] = {
    Seq(
      AdminExtensionEntity(CloudApimWafConfig.resource(env, datastores, states)),
    )
  }

  def getResourceCode(path: String): String = {
    implicit val ec = env.otoroshiExecutionContext
    implicit val mat = env.otoroshiMaterializer
    env.environment.resourceAsStream(path)
      .map(stream => StreamConverters.fromInputStream(() => stream).runFold(ByteString.empty)(_++_).awaitf(10.seconds).utf8String)
      .getOrElse(s"'resource ${path} not found !'")
  }

  lazy val wafConfigsPageCode = getResourceCode("cloudapim/extensions/waf/WafConfigsPage.js")
  lazy val imgCode = getResourceCode("cloudapim/extensions/waf/icon.svg")

  override def assets(): Seq[AdminExtensionAssetRoute] = Seq(
    AdminExtensionAssetRoute(
      path = "/extensions/assets/cloud-apim/extensions/waf/icon.svg",
      handle = (ctx: AdminExtensionRouterContext[AdminExtensionAssetRoute], req: RequestHeader) => {
        Results.Ok(imgCode).as("image/svg+xml").vfuture
      }
    ),
    AdminExtensionAssetRoute(
      path = "/extensions/assets/cloud-apim/extensions/waf/extension.js",
      handle = (ctx: AdminExtensionRouterContext[AdminExtensionAssetRoute], req: RequestHeader) => {
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
             |    return {
             |      id: extensionId,
             |      categories:[{
             |        title: 'WAF',
             |        description: 'All the features provided by the Cloud APIM WAF extension',
             |        features: [
             |          {
             |            title: 'Cloud APIM WAF configs',
             |            description: 'All your Cloud APIM WAF configs',
             |            absoluteImg: '/extensions/assets/cloud-apim/extensions/waf/icon.svg',
             |            link: '/extensions/cloud-apim/waf/wafconfigs',
             |            display: () => true,
             |            icon: () => 'fa-atom',
             |          }
             |        ]
             |      }],
             |      features: [
             |        {
             |          title: 'Cloud APIM WAF configs',
             |          description: 'All your Cloud APIM WAF configs',
             |          absoluteImg: '/extensions/assets/cloud-apim/extensions/waf/icon.svg',
             |          link: '/extensions/cloud-apim/waf/wafconfigs',
             |          display: () => true,
             |          icon: () => 'fa-atom',
             |        }
             |      ],
             |      sidebarItems: [
             |        {
             |          title: 'Cloud APIM WAF configs',
             |          text: 'All your Cloud APIM WAF configs',
             |          path: 'extensions/cloud-apim/waf/wafconfigs',
             |          icon: 'atom'
             |        }
             |      ],
             |      searchItems: [
             |        {
             |          action: () => {
             |            window.location.href = `/bo/dashboard/extensions/cloud-apim/waf/wafconfigs`
             |          },
             |          env: React.createElement('span', { className: "fas fa-atom" }, null),
             |          label: 'Cloud APIM WAFs configs',
             |          value: 'wafconfigs',
             |        }
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
             |        }
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
      handle = handleCompile
    ),
    AdminExtensionBackofficeAuthRoute(
      method = "POST",
      path = "/extensions/cloud-apim/extensions/waf/utils/_test",
      wantsBody = true,
      handle = handleTest
    ),
  )

  def handleCompile(ctx: AdminExtensionRouterContext[AdminExtensionBackofficeAuthRoute], req: RequestHeader, user: Option[BackOfficeUser], body: Option[Source[ByteString, _]]): Future[Result] = {
    implicit val ec = env.otoroshiExecutionContext
    implicit val mat = env.otoroshiMaterializer
    implicit val ev = env
    (body match {
      case None => Results.Ok(Json.obj("done" -> false, "error" -> "no body")).vfuture
      case Some(bodySource) => bodySource.runFold(ByteString.empty)(_ ++ _).flatMap { bodyRaw =>
        val bodyJson = bodyRaw.utf8String.parseJson
        val rules = bodyJson.select("rules").asOpt[List[String]].getOrElse(List.empty).filterNot(_.trim.startsWith("@import_preset ")).mkString("\n\n")
        (SecLang.parse(rules) match {
          case Left(err) => Results.Ok(Json.obj("done" -> false, "error" -> err))
          case Right(conf) => Try(SecLang.compile(conf)) match {
            case Failure(err) => Results.Ok(Json.obj("done" -> false, "error" -> err.getMessage))
            case Success(_) => Results.Ok(Json.obj("done" -> true))
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

  def handleTest(ctx: AdminExtensionRouterContext[AdminExtensionBackofficeAuthRoute], req: RequestHeader, user: Option[BackOfficeUser], body: Option[Source[ByteString, _]]): Future[Result] = {
    implicit val ec = env.otoroshiExecutionContext
    implicit val mat = env.otoroshiMaterializer
    implicit val ev = env
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
          headers = com.cloud.apim.seclang.model.Headers(request.select("headers").asOpt[Map[String, String]].map(_.mapValues(v => List(v))).getOrElse(Map.empty)),
          cookies = request.select("cookies").asOpt[Map[String, String]].map(_.mapValues(v => List(v))).getOrElse(Map.empty),
          query = request.select("query").asOpt[Map[String, String]].map(_.mapValues(v => List(v))).getOrElse(Map.empty),
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

  override def toJson(implicit env: Env): JsValue = {
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
