package com.cloud.apim.otoroshi.extensions.waf.studio

import org.apache.pekko.stream.Materializer
import org.apache.pekko.stream.scaladsl.{Source, StreamConverters}
import org.apache.pekko.util.ByteString
import com.cloud.apim.otoroshi.extensions.waf.analytics.{PostureReport, RouteGovernance, RoutePosture}
import otoroshi.env.Env
import otoroshi.models.BackOfficeUser
import otoroshi.next.extensions.*
import otoroshi.next.models.{NgPluginInstance, NgPluginInstanceConfig, NgPlugins, NgRoute}
import otoroshi.utils.cache.types.UnboundedTrieMap
import otoroshi.utils.syntax.implicits.*
import otoroshi_plugins.com.cloud.apim.otoroshi.extensions.waf.CloudApimWafExtension
import otoroshi_plugins.com.cloud.apim.otoroshi.extensions.waf.plugins.{
  CloudApimSecuritySuiteGlobalPreset,
  CloudApimSecuritySuiteGlobalPresetConfig,
  CloudApimSecuritySuiteGlobalRule
}
import play.api.libs.json.*
import play.api.mvc.{RequestHeader, Result, Results}

import scala.concurrent.duration.DurationInt
import scala.concurrent.{ExecutionContext, Future}

/**
 * Global configuration of Threat Studio, edited from the Otoroshi danger zone and stored in
 * `globalConfig.extensions.cloud-apim_extensions_Waf.threatstudio`.
 */
case class ThreatStudioConfig(raw: JsObject) {
  def enabled: Boolean = raw.select("enabled").asOptBoolean.getOrElse(true)
  def frontendJson: JsObject = Json.obj("enabled" -> enabled)
}

object ThreatStudioConfig {
  val configKey: String = CloudApimWafExtension.extensionId.value.replace(".", "_")
  def current(env: Env): ThreatStudioConfig = {
    val gc = env.datastores.globalConfigDataStore.latest()(using env.otoroshiExecutionContext, env)
    ThreatStudioConfig(gc.extensions.get(configKey).flatMap(_.select("threatstudio").asOpt[JsObject]).getOrElse(Json.obj()))
  }
}

/**
 * Threat Studio: one console for the whole suite, organised by *who is protected* rather than by
 * *which entity configures it*.
 *
 * It stores nothing of its own. A workspace is a rule of the global preset table, and the routes it
 * covers are that rule's selectors resolved against the router — which is the one computation only
 * this side can do, since the selectors go through the expression language and the table lives on
 * the global configuration. Everything else the front needs is already an admin API: the suite's
 * entities, the incident console, tuning, learning, and the analytics queries.
 *
 * The two endpoints here are therefore the table, read and written. Reading it resolves it; writing
 * it touches the global preset slot of the global plugins and nothing else on the global config.
 */
class ThreatStudio(env: Env) {

  private given ec: ExecutionContext = env.otoroshiExecutionContext
  private given mat: Materializer    = env.otoroshiMaterializer
  private given ev: Env              = env

  val basePath   = "/extensions/cloud-apim/threat-studio"
  val assetsPath = "/extensions/assets/cloud-apim/extensions/waf/studio"
  val apiPath    = "/extensions/cloud-apim/extensions/waf/studio"

  private val resourcesRoot = "cloudapim/extensions/waf/studio"
  private val assetsCache   = new UnboundedTrieMap[String, Option[ByteString]]()

  /**
   * Where the theme choice is kept, on the otoroshi user.
   *
   * The browser keeps its own copy under the same key in `localStorage`, and that is the one the page
   * reads before the bundle loads, so a reload never flashes the wrong theme. This copy is what makes
   * the choice follow the user to another browser.
   */
  val themePreference = "threat_studio_theme"

  private def readResource(path: String): Option[ByteString] = {
    def read() = env.environment.resourceAsStream(path).map { stream =>
      StreamConverters.fromInputStream(() => stream).runFold(ByteString.empty)(_ ++ _).awaitf(10.seconds)
    }
    if (env.isDev) read() else assetsCache.getOrElseUpdate(path, read())
  }

  private def unauthorized: Future[Result] =
    Results.Unauthorized(Json.obj("error" -> "unauthorized", "error_description" -> "you're not logged in")).vfuture

  private def forbidden(what: String): Future[Result] =
    Results.Forbidden(Json.obj("error" -> "forbidden", "error_description" -> what)).vfuture

  /////////////////////////////////////////////////////////////////////////////////////////////////
  // page and assets
  /////////////////////////////////////////////////////////////////////////////////////////////////

  private def entryAssets(): (Seq[String], Seq[String]) = {
    readResource(s"$resourcesRoot/manifest.json").map(_.utf8String.parseJson) match {
      case None           => (Seq.empty, Seq.empty)
      case Some(manifest) =>
        val entry = manifest.select("src/main.jsx").asOpt[JsObject].getOrElse(Json.obj())
        val js    = entry.select("file").asOptString.toSeq.map(f => s"$assetsPath/$f")
        val css   = entry.select("css").asOpt[Seq[String]].getOrElse(Seq.empty).map(f => s"$assetsPath/$f")
        (js, css)
    }
  }

  private def themeOf(u: BackOfficeUser): Future[String] =
    env.datastores.adminPreferencesDatastore
      .getPreference(u.email, themePreference)
      .map(_.flatMap(_.asOpt[String]).filter(t => t == "light" || t == "dark" || t == "system").getOrElse("system"))
      .recover { case _ => "system" }

  private def bootstrapJson(u: BackOfficeUser, config: ThreatStudioConfig, theme: String): JsObject = Json.obj(
    "theme"       -> theme,
    "basePath"    -> basePath,
    "adminUrl"    -> "/bo/dashboard",
    "apiPath"     -> apiPath,
    "extensionId" -> CloudApimWafExtension.extensionId.value,
    "user"        -> Json.obj(
      "email"      -> u.email,
      "name"       -> u.name,
      "superAdmin" -> u.rights.superAdmin,
      "rights"     -> u.rights.json
    ),
    "config"      -> config.frontendJson,
    "otoroshi"    -> Json.obj("version" -> env.otoroshiVersion, "domain" -> env.domain)
  )

  // used by the vite dev server (see ui/threat-studio/README.md), which cannot get the values
  // injected in the page
  def handleBootstrap(user: Option[BackOfficeUser]): Future[Result] = user match {
    case None    => unauthorized
    case Some(u) => themeOf(u).map(theme => Results.Ok(bootstrapJson(u, ThreatStudioConfig.current(env), theme)))
  }

  def handlePage(req: RequestHeader, user: Option[BackOfficeUser]): Future[Result] = user match {
    case None    =>
      Results
        .Redirect("/bo/dashboard")
        .addingToSession("bo-redirect-after-login" -> s"${env.rootScheme}${req.host}${req.uri}")(using req)
        .vfuture
    case Some(u) =>
      val config = ThreatStudioConfig.current(env)
      if (!config.enabled) {
        Results.NotFound(Json.obj("error" -> "not_found", "error_description" -> "Threat Studio is disabled")).vfuture
      } else
        themeOf(u).map { theme =>
          val bootstrap         = bootstrapJson(u, config, theme).stringify.replace("<", "\\u003c")
          val (scripts, styles) = entryAssets()
          val html              =
            s"""<!doctype html>
               |<html lang="en">
               |<head>
               |  <meta charset="utf-8" />
               |  <meta name="viewport" content="width=device-width, initial-scale=1" />
               |  <title>Threat Studio - Otoroshi</title>
               |  <link rel="icon" type="image/svg+xml" href="/extensions/assets/cloud-apim/extensions/waf/icon.svg" />
               |  <script>window.__THREAT_STUDIO__ = $bootstrap;(function(){try{var t=null;try{t=window.localStorage.getItem('threat_studio_theme')}catch(e){}if(t!=='dark'&&t!=='light'){t=window.__THREAT_STUDIO__.theme}if(t!=='dark'&&t!=='light'){t=window.matchMedia&&window.matchMedia('(prefers-color-scheme: dark)').matches?'dark':'light'}document.documentElement.setAttribute('data-theme',t)}catch(e){}})();</script>
               |  ${styles.map(s => s"""<link rel="stylesheet" href="$s" />""").mkString("\n  ")}
               |  ${scripts.map(s => s"""<script type="module" src="$s"></script>""").mkString("\n  ")}
               |</head>
               |<body>
               |  <div id="root"></div>
               |</body>
               |</html>""".stripMargin
          Results.Ok(html).as("text/html; charset=utf-8").withHeaders("Cache-Control" -> "no-cache, no-store")
        }
  }

  private def contentTypeOf(path: String): String = path.split("\\.").lastOption.map(_.toLowerCase) match {
    case Some("js")    => "application/javascript"
    case Some("css")   => "text/css"
    case Some("json")  => "application/json"
    case Some("svg")   => "image/svg+xml"
    case Some("png")   => "image/png"
    case Some("woff2") => "font/woff2"
    case Some("woff")  => "font/woff"
    case _             => "application/octet-stream"
  }

  def handleAsset(req: RequestHeader): Future[Result] = {
    val path = req.path.stripPrefix(assetsPath).stripPrefix("/")
    if (path.isEmpty || path.contains("..") || path == "manifest.json") {
      Results.NotFound("not found").vfuture
    } else {
      readResource(s"$resourcesRoot/$path") match {
        case None        => Results.NotFound("not found").vfuture
        case Some(bytes) =>
          val immutable = path.startsWith("assets/")
          Results
            .Ok(bytes)
            .as(contentTypeOf(path))
            .withHeaders(
              "Cache-Control" -> (if (immutable && !env.isDev) "public, max-age=31536000, immutable" else "no-cache")
            )
            .vfuture
      }
    }
  }

  /////////////////////////////////////////////////////////////////////////////////////////////////
  // the table, resolved
  /////////////////////////////////////////////////////////////////////////////////////////////////

  private def summaryOf(postures: Seq[RoutePosture]): JsObject = Json.obj(
    "total"     -> postures.size,
    "covered"   -> postures.count(_.covered),
    "enforcing" -> postures.count(_.enforcing)
  )

  private def routeRef(route: NgRoute): JsObject =
    Json.obj("id" -> route.id, "name" -> route.name)

  /**
   * Every rule with the routes it wins, the routes it merely matches, and whether it can be reached
   * at all.
   *
   * `matches` and `claims` are reported separately on purpose. A rule matching forty routes and
   * winning none is not a mistake the table makes visible on its own — it looks exactly like a rule
   * that works — and it is the single most likely thing to be wrong about an ordered table.
   */
  def workspacesJson: JsValue = {
    val routes   = env.proxyState.allRoutes()
    val table    = PostureReport.table(routes)
    val postures = routes.map(r => r -> PostureReport.of(r, table.governanceOf.getOrElse(r.id, RouteGovernance.none))).toMap

    val claimedBy = routes.groupBy(r => table.governanceOf.get(r.id).flatMap(_.workspaceId))
    val matchedBy = routes
      .flatMap(r => table.governanceOf.get(r.id).toSeq.flatMap(g => (g.workspaceId.toSeq ++ g.alsoMatched).map(_ -> r)))
      .groupBy(_._1)
      .view
      .mapValues(_.map(_._2))
      .toMap

    // a rule sitting under an enabled rule that claims every route can never be reached. that is the
    // one form of shadowing that is structural rather than a property of the routes of the day
    val catchAllAt = table.config.rules.indexWhere(r => r.enabled && r.targets.isEmpty)

    val workspaces = table.config.rules.zipWithIndex.map { case (rule, idx) =>
      val claimed = claimedBy.getOrElse(rule.id.some, Seq.empty)
      val matched = matchedBy.getOrElse(rule.id, Seq.empty)
      Json.obj(
        "id"          -> rule.id,
        "name"        -> Option(rule.name).filter(_.nonEmpty).getOrElse(rule.id),
        "index"       -> idx,
        "enabled"     -> rule.enabled,
        "skip"        -> rule.skip,
        "targets"     -> JsArray(rule.targets.map(CloudApimSecuritySuiteTargetJson.write)),
        "preset"      -> rule.preset.json,
        "route_only"  -> rule.routeOnly,
        "unreachable" -> (catchAllAt >= 0 && idx > catchAllAt),
        "claims"      -> JsArray(claimed.map(routeRef)),
        "matches"     -> JsArray(matched.map(routeRef)),
        "summary"     -> summaryOf(claimed.map(postures.apply))
      )
    }

    val unclaimed   = routes.filter { r =>
      val g = table.governanceOf.getOrElse(r.id, RouteGovernance.none)
      g.workspaceId.isEmpty && !g.selfManaged && !postures(r).covered
    }
    val selfManaged = routes.filter(r => table.governanceOf.get(r.id).exists(_.selfManaged))
    val skipped     = routes.filter { r =>
      val g = table.governanceOf.getOrElse(r.id, RouteGovernance.none)
      g.workspaceId.isEmpty && !g.selfManaged && postures(r).covered
    }

    Json.obj(
      "installed"             -> table.installed,
      "enabled"               -> table.enabled,
      "dynamic"               -> table.dynamic,
      "skip_protected_routes" -> table.config.skipProtectedRoutes,
      "plugin_id"             -> CloudApimSecuritySuiteGlobalPreset.pluginId,
      "workspaces"            -> JsArray(workspaces),
      "fleet"                 -> Json.obj(
        "summary"      -> summaryOf(routes.map(postures.apply)),
        "governed"     -> routes.count(r => table.governanceOf.get(r.id).exists(_.workspaceId.isDefined)),
        "unclaimed"    -> JsArray(unclaimed.map(routeRef)),
        "self_managed" -> JsArray(selfManaged.map(routeRef)),
        "protected_outside_table" -> JsArray(skipped.map(routeRef))
      )
    )
  }

  def handleWorkspaces(user: Option[BackOfficeUser]): Future[Result] = user match {
    case None    => unauthorized
    case Some(_) => Results.Ok(workspacesJson).vfuture
  }

  /** The routes one workspace governs, each with the posture it actually ends up with. */
  def handleWorkspaceRoutes(
      ctx: AdminExtensionRouterContext[AdminExtensionBackofficeAuthRoute],
      user: Option[BackOfficeUser]
  ): Future[Result] = user match {
    case None    => unauthorized
    case Some(_) =>
      val id       = ctx.named("id").getOrElse("--")
      val routes   = env.proxyState.allRoutes()
      val table    = PostureReport.table(routes)
      if (!table.config.rules.exists(_.id == id)) {
        Results.NotFound(Json.obj("error" -> "not_found", "error_description" -> "no such workspace")).vfuture
      } else {
        val claimed = routes.filter(r => table.governanceOf.get(r.id).exists(_.workspaceId.contains(id)))
        val also    = routes.filter(r => table.governanceOf.get(r.id).exists(_.alsoMatched.contains(id)))
        Results
          .Ok(
            Json.obj(
              "routes"       -> JsArray(
                claimed.map(r => PostureReport.of(r, table.governanceOf.getOrElse(r.id, RouteGovernance.none)).json)
              ),
              // matched but lost to a rule above: the rows that explain a workspace that looks empty
              "also_matched" -> JsArray(
                also.map(r => PostureReport.of(r, table.governanceOf.getOrElse(r.id, RouteGovernance.none)).json)
              )
            )
          )
          .vfuture
      }
  }

  /////////////////////////////////////////////////////////////////////////////////////////////////
  // the table, written
  /////////////////////////////////////////////////////////////////////////////////////////////////

  /**
   * Writes the table back, touching the global preset slot and nothing else.
   *
   * The whole table is replaced in one call rather than patched rule by rule, because the order of
   * the rules *is* the semantics: a partial update would let two clients reorder against each other
   * and produce a table neither of them meant.
   */
  def writeTable(config: CloudApimSecuritySuiteGlobalPresetConfig, slotEnabled: Option[Boolean] = None): Future[Unit] = {
    env.datastores.globalConfigDataStore.singleton().flatMap { gc =>
      val pluginId  = CloudApimSecuritySuiteGlobalPreset.pluginId
      val slots     = NgPlugins.readFrom(gc.plugins.config.select("ng")).slots
      val existing  = slots.find(_.plugin == pluginId)
      val updated   = NgPluginInstance(
        plugin = pluginId,
        enabled = slotEnabled.orElse(existing.map(_.enabled)).getOrElse(true),
        debug = existing.exists(_.debug),
        include = existing.map(_.include).getOrElse(Seq.empty),
        exclude = existing.map(_.exclude).getOrElse(Seq.empty),
        boundListeners = existing.map(_.boundListeners).getOrElse(Seq.empty),
        config = NgPluginInstanceConfig(config.json.asObject),
        pluginIndex = existing.flatMap(_.pluginIndex)
      )
      val newSlots  = if (existing.isDefined) slots.map(s => if (s.plugin == pluginId) updated else s) else slots :+ updated
      val newConfig = gc.plugins.config.asObject ++ Json.obj("ng" -> JsArray(newSlots.map(_.json)))
      env.datastores.globalConfigDataStore.set(gc.copy(plugins = gc.plugins.copy(config = newConfig))).map(_ => ())
    }
  }

  /** Ids are the studio's handle on a rule, so a table saved without them is given them here. */
  private def withIds(rules: Seq[CloudApimSecuritySuiteGlobalRule]): Seq[CloudApimSecuritySuiteGlobalRule] = {
    val seen = scala.collection.mutable.HashSet.empty[String]
    rules.zipWithIndex.map { case (rule, idx) =>
      val candidate = Option(rule.id).map(_.trim).filter(_.nonEmpty).getOrElse(s"rule_${idx}_${otoroshi.security.IdGenerator.token(8).toLowerCase}")
      val unique    = if (seen.contains(candidate)) s"${candidate}_$idx" else candidate
      seen.add(unique)
      rule.copy(id = unique)
    }
  }

  def handleSaveWorkspaces(
      user: Option[BackOfficeUser],
      body: Option[Source[ByteString, ?]]
  ): Future[Result] = user match {
    case None                            => unauthorized
    case Some(u) if !u.rights.superAdmin =>
      // the table lives on the global configuration, which is the danger zone by another name
      forbidden("editing the global preset table requires a super admin")
    case Some(_)                         =>
      body match {
        case None         => Results.BadRequest(Json.obj("error" -> "bad_request", "error_description" -> "no body")).vfuture
        case Some(source) =>
          source.runFold(ByteString.empty)(_ ++ _).flatMap { raw =>
            val json = raw.utf8String.parseJson
            CloudApimSecuritySuiteGlobalPresetConfig.format.reads(json) match {
              case JsError(err)          =>
                Results
                  .BadRequest(Json.obj("error" -> "bad_request", "error_description" -> s"invalid table: $err"))
                  .vfuture
              case JsSuccess(config, _) =>
                val cleaned = config.copy(rules = withIds(config.rules))
                writeTable(cleaned, json.select("slot_enabled").asOpt[Boolean]).map(_ => Results.Ok(workspacesJson))
            }
          }
      }
  }

  /////////////////////////////////////////////////////////////////////////////////////////////////
  // routes
  /////////////////////////////////////////////////////////////////////////////////////////////////

  def backofficeRoutes: Seq[AdminExtensionBackofficeAuthRoute] = Seq(
    AdminExtensionBackofficeAuthRoute("GET", basePath, wantsBody = false, handle = (_, req, user, _) => handlePage(req, user)),
    AdminExtensionBackofficeAuthRoute("GET", s"$basePath/*", wantsBody = false, handle = (_, req, user, _) => handlePage(req, user)),
    AdminExtensionBackofficeAuthRoute("GET", s"$apiPath/bootstrap", wantsBody = false, handle = (_, _, user, _) => handleBootstrap(user)),
    AdminExtensionBackofficeAuthRoute("GET", s"$apiPath/workspaces", wantsBody = false, handle = (_, _, user, _) => handleWorkspaces(user)),
    AdminExtensionBackofficeAuthRoute("PUT", s"$apiPath/workspaces", wantsBody = true, handle = (_, _, user, body) => handleSaveWorkspaces(user, body)),
    AdminExtensionBackofficeAuthRoute(
      "GET",
      s"$apiPath/workspaces/:id/routes",
      wantsBody = false,
      handle = (ctx, _, user, _) => handleWorkspaceRoutes(ctx, user)
    )
  )

  def assetRoutes: Seq[AdminExtensionAssetRoute] = Seq(
    AdminExtensionAssetRoute(s"$assetsPath/*", handle = (_, req) => handleAsset(req))
  )
}

/** The target format lives on the plugin; this is only the writer the studio payload needs. */
private object CloudApimSecuritySuiteTargetJson {
  def write(t: otoroshi_plugins.com.cloud.apim.otoroshi.extensions.waf.plugins.CloudApimSecuritySuiteTarget): JsValue =
    otoroshi_plugins.com.cloud.apim.otoroshi.extensions.waf.plugins.CloudApimSecuritySuiteTarget.format.writes(t)
}
