package com.cloud.apim.otoroshi.extensions.waf.entities

import com.cloud.apim.otoroshi.extensions.waf.security.{SecurityDatastores, SecurityState}
import otoroshi.api.*
import otoroshi.env.Env
import otoroshi.models.*
import otoroshi.next.extensions.*
import otoroshi.security.IdGenerator
import otoroshi.storage.*
import otoroshi.utils.syntax.implicits.*
import play.api.libs.json.*

import scala.concurrent.duration.{DurationLong, FiniteDuration}
import scala.util.{Failure, Success, Try}

/**
 * How a suspect caller is asked to prove something before proceeding.
 *
 * Two kinds. `pow` is self-contained: we generate the puzzle, the browser solves it, we verify —
 * no third party, no external script, no personal data. `vendor` is the generic escape hatch for
 * deployments whose procurement requires a named product: it embeds a widget and checks the answer
 * against a siteverify endpoint.
 *
 * There is deliberately no per-vendor code. A vendor is a set of URLs and field names, which makes
 * adding one a configuration change and stops the extension shipping hardcoded endpoints that rot.
 */
final case class ChallengeProvider(
    location: EntityLocation = EntityLocation.default,
    id: String,
    name: String,
    description: String = "",
    tags: Seq[String] = Seq.empty,
    metadata: Map[String, String] = Map.empty,
    enabled: Boolean = true,
    kind: String = "pow",
    // proof of work
    difficultyFloor: Int = 18,
    difficultyCeiling: Int = 24,
    challengeTtlSeconds: Long = 300L,
    // clearance
    clearanceTtlSeconds: Long = 1800L,
    cookieName: String = "cloud-apim-clearance",
    secret: String = "",
    bindIp: Boolean = true,
    bindUa: Boolean = true,
    // vendor widget
    presetRef: Option[String] = None,
    widgetScriptUrl: String = "",
    widgetHtml: String = "",
    responseField: String = "",
    verifyUrl: String = "",
    siteKey: String = "",
    secretKey: String = "",
    // interstitial copy
    title: String = "Checking your browser",
    message: String = "This will take a moment. No data about you leaves this page."
) extends EntityLocationSupport {

  override def internalId: String               = id
  override def json: JsValue                    = ChallengeProvider.format.writes(this)
  override def theName: String                  = name
  override def theDescription: String           = description
  override def theTags: Seq[String]             = tags
  override def theMetadata: Map[String, String] = metadata

  def isPow: Boolean                   = kind.trim.equalsIgnoreCase("pow")
  def isVendor: Boolean                = !isPow
  def clearanceTtl: FiniteDuration     = clearanceTtlSeconds.max(30L).seconds
  def challengeTtl: FiniteDuration     = challengeTtlSeconds.max(30L).seconds
  def secretOr(fallback: String): String = if (secret.trim.isEmpty) fallback else secret.trim

  def usable: Boolean =
    enabled && (isPow || (verifyUrl.trim.nonEmpty && secretKey.trim.nonEmpty && widgetScriptUrl.trim.nonEmpty))
}

object ChallengeProvider {

  val kinds: Seq[String] = Seq("pow", "vendor")

  val format: Format[ChallengeProvider] = new Format[ChallengeProvider] {
    override def writes(o: ChallengeProvider): JsValue = o.location.jsonWithKey ++ Json.obj(
      "id"                    -> o.id,
      "name"                  -> o.name,
      "description"           -> o.description,
      "metadata"              -> o.metadata,
      "tags"                  -> JsArray(o.tags.map(JsString.apply)),
      "enabled"               -> o.enabled,
      "kind"                  -> o.kind,
      "difficulty_floor"      -> o.difficultyFloor,
      "difficulty_ceiling"    -> o.difficultyCeiling,
      "challenge_ttl_seconds" -> o.challengeTtlSeconds,
      "clearance_ttl_seconds" -> o.clearanceTtlSeconds,
      "cookie_name"           -> o.cookieName,
      "secret"                -> o.secret,
      "bind_ip"               -> o.bindIp,
      "bind_ua"               -> o.bindUa,
      "preset_ref"            -> o.presetRef,
      "widget_script_url"     -> o.widgetScriptUrl,
      "widget_html"           -> o.widgetHtml,
      "response_field"        -> o.responseField,
      "verify_url"            -> o.verifyUrl,
      "site_key"              -> o.siteKey,
      "secret_key"            -> o.secretKey,
      "title"                 -> o.title,
      "message"               -> o.message
    )

    override def reads(json: JsValue): JsResult[ChallengeProvider] = Try {
      ChallengeProvider(
        location = otoroshi.models.EntityLocation.readFromKey(json),
        id = json.select("id").asString,
        name = json.select("name").asString,
        description = json.select("description").asOpt[String].getOrElse(""),
        metadata = json.select("metadata").asOpt[Map[String, String]].getOrElse(Map.empty),
        tags = json.select("tags").asOpt[Seq[String]].getOrElse(Seq.empty),
        enabled = json.select("enabled").asOpt[Boolean].getOrElse(true),
        kind = json.select("kind").asOpt[String].getOrElse("pow"),
        difficultyFloor = json.select("difficulty_floor").asOpt[Int].getOrElse(18),
        difficultyCeiling = json.select("difficulty_ceiling").asOpt[Int].getOrElse(24),
        challengeTtlSeconds = json.select("challenge_ttl_seconds").asOpt[Long].getOrElse(300L),
        clearanceTtlSeconds = json.select("clearance_ttl_seconds").asOpt[Long].getOrElse(1800L),
        cookieName = json.select("cookie_name").asOpt[String].getOrElse("cloud-apim-clearance"),
        secret = json.select("secret").asOpt[String].getOrElse(""),
        bindIp = json.select("bind_ip").asOpt[Boolean].getOrElse(true),
        bindUa = json.select("bind_ua").asOpt[Boolean].getOrElse(true),
        presetRef = json.select("preset_ref").asOpt[String].filter(_.trim.nonEmpty),
        widgetScriptUrl = json.select("widget_script_url").asOpt[String].getOrElse(""),
        widgetHtml = json.select("widget_html").asOpt[String].getOrElse(""),
        responseField = json.select("response_field").asOpt[String].getOrElse(""),
        verifyUrl = json.select("verify_url").asOpt[String].getOrElse(""),
        siteKey = json.select("site_key").asOpt[String].getOrElse(""),
        secretKey = json.select("secret_key").asOpt[String].getOrElse(""),
        title = json.select("title").asOpt[String].getOrElse("Checking your browser"),
        message = json
          .select("message")
          .asOpt[String]
          .getOrElse("This will take a moment. No data about you leaves this page.")
      )
    } match {
      case Failure(ex)    => JsError(ex.getMessage)
      case Success(value) => JsSuccess(value)
    }
  }

  def template(env: Env): ChallengeProvider = ChallengeProvider(
    id = IdGenerator.namedId("challenge-provider", env),
    name = "Proof of work",
    description = "Self-contained proof-of-work challenge, no third party involved"
  )

  def resource(env: Env, datastores: SecurityDatastores, states: SecurityState): Resource = {
    Resource(
      "ChallengeProvider",
      "challenge-providers",
      "challenge-provider",
      "waf.extensions.cloud-apim.com",
      ResourceVersion("v1", true, false, true),
      GenericResourceAccessApiWithState[ChallengeProvider](
        format = ChallengeProvider.format,
        clazz = classOf[ChallengeProvider],
        keyf = id => datastores.challengeProviderDatastore.key(id),
        extractIdf = c => datastores.challengeProviderDatastore.extractId(c),
        extractIdJsonf = json => json.select("id").asString,
        idFieldNamef = () => "id",
        tmpl = (_, _, _) => ChallengeProvider.template(env).json,
        canRead = true,
        canCreate = true,
        canUpdate = true,
        canDelete = true,
        canBulk = true,
        stateAll = () => states.allChallengeProviders(),
        stateOne = id => states.challengeProvider(id),
        stateUpdate = values => states.updateChallengeProviders(values)
      )
    )
  }
}

trait ChallengeProviderDatastore extends BasicStore[ChallengeProvider]

class KvChallengeProviderDatastore(extensionId: AdminExtensionId, redisCli: RedisLike, _env: Env)
    extends ChallengeProviderDatastore
    with RedisLikeStore[ChallengeProvider] {
  override def fmt: Format[ChallengeProvider]             = ChallengeProvider.format
  override def redisLike(using env: Env): RedisLike       = redisCli
  override def key(id: String): String                    = s"${_env.storageRoot}:extensions:${extensionId.cleanup}:challengeproviders:$id"
  override def extractId(value: ChallengeProvider): String = value.id
}

/**
 * Ready-to-edit vendor settings.
 *
 * Shipped as data, exactly like the threat feed catalog and for the same reason: endpoints and
 * widget URLs change, and a provider that moves should cost one field rather than a release. Every
 * entry is created disabled — none of them can work before you paste your own keys in.
 */
final case class ChallengePreset(
    id: String,
    name: String,
    description: String,
    origin: String,
    widgetScriptUrl: String,
    widgetHtml: String,
    responseField: String,
    verifyUrl: String,
    notes: Option[String] = None
) {
  def json: JsValue = Json.obj(
    "id"                -> id,
    "name"              -> name,
    "description"       -> description,
    "origin"            -> origin,
    "widget_script_url" -> widgetScriptUrl,
    "widget_html"       -> widgetHtml,
    "response_field"    -> responseField,
    "verify_url"        -> verifyUrl,
    "notes"             -> notes
  )
}

object ChallengePresets {

  val entries: Seq[ChallengePreset] = Seq(
    ChallengePreset(
      id = "friendly-captcha",
      name = "Friendly Captcha",
      description = "German provider, proof-of-work based, GDPR by default. The closest commercial equivalent to the built-in pow kind.",
      origin = "Germany (Munich)",
      widgetScriptUrl = "https://cdn.jsdelivr.net/npm/friendly-challenge@0.9.12/widget.module.min.js",
      widgetHtml = """<div class="frc-captcha" data-sitekey="__SITE_KEY__"></div>""",
      responseField = "frc-captcha-solution",
      verifyUrl = "https://api.friendlycaptcha.com/api/v1/siteverify",
      notes = Some("Friendly Captcha has more than one API generation. Confirm the widget and siteverify urls against your account before enabling.")
    ),
    ChallengePreset(
      id = "captcha-eu",
      name = "captcha.eu",
      description = "European managed provider with Austria-hosted processing included in its commercial plans.",
      origin = "Austria",
      widgetScriptUrl = "",
      widgetHtml = "",
      responseField = "",
      verifyUrl = "",
      notes = Some("Endpoints are not shipped: take the widget url, response field and siteverify url from your captcha.eu dashboard and paste them here.")
    ),
    ChallengePreset(
      id = "turnstile",
      name = "Cloudflare Turnstile",
      description = "Cloudflare's challenge. Strong behavioural signals, US provider — check your privacy posture before using it.",
      origin = "United States",
      widgetScriptUrl = "https://challenges.cloudflare.com/turnstile/v0/api.js",
      widgetHtml = """<div class="cf-turnstile" data-sitekey="__SITE_KEY__"></div>""",
      responseField = "cf-turnstile-response",
      verifyUrl = "https://challenges.cloudflare.com/turnstile/v0/siteverify"
    ),
    ChallengePreset(
      id = "hcaptcha",
      name = "hCaptcha",
      description = "Widely deployed alternative to reCAPTCHA. US provider.",
      origin = "United States",
      widgetScriptUrl = "https://js.hcaptcha.com/1/api.js",
      widgetHtml = """<div class="h-captcha" data-sitekey="__SITE_KEY__"></div>""",
      responseField = "h-captcha-response",
      verifyUrl = "https://api.hcaptcha.com/siteverify"
    )
  )

  def find(id: String): Option[ChallengePreset] = entries.find(_.id == id)

  def json: JsValue = Json.obj("entries" -> JsArray(entries.map(_.json)))

  def apply(preset: ChallengePreset, id: String): ChallengeProvider = ChallengeProvider(
    id = id,
    name = preset.name,
    description = preset.description,
    tags = Seq("preset", "vendor"),
    // never enabled on creation: without your own keys it cannot work, and a challenge that
    // cannot verify would lock every suspect caller out
    enabled = false,
    kind = "vendor",
    presetRef = Some(preset.id),
    widgetScriptUrl = preset.widgetScriptUrl,
    widgetHtml = preset.widgetHtml,
    responseField = preset.responseField,
    verifyUrl = preset.verifyUrl
  )
}
