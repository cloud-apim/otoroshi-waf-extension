package com.cloud.apim.otoroshi.extensions.waf.entities

import otoroshi.api.*
import otoroshi.env.Env
import otoroshi.models.*
import otoroshi.next.extensions.*
import otoroshi.security.IdGenerator
import otoroshi.storage.*
import otoroshi.utils.syntax.implicits.*
import otoroshi_plugins.com.cloud.apim.otoroshi.extensions.waf.*
import play.api.libs.json.*

import scala.util.{Failure, Success, Try}

/**
 * A named, reusable body of SecLang.
 *
 * Rules used to live as a string array inside `WafConfig`, which made them impossible to share: two
 * routes wanting the same baseline had two copies of it, and a fix landed in one of them. As their
 * own entity they compose — a config lists the rulesets it wants, in order — and they get the
 * things any entity gets here: an admin API, import/export, Kubernetes CRDs, tags to organise by.
 *
 * Nothing forces the move. A config that carries its rules inline keeps working exactly as it did,
 * and its inline rules are appended after whatever rulesets it references.
 */
final case class WafRuleset(
    location: EntityLocation = EntityLocation.default,
    id: String,
    name: String,
    description: String = "",
    tags: Seq[String] = Seq.empty,
    metadata: Map[String, String] = Map.empty,
    enabled: Boolean = true,
    rules: Seq[String] = Seq.empty
) extends EntityLocationSupport {
  override def internalId: String               = id
  override def json: JsValue                    = WafRuleset.format.writes(this)
  override def theName: String                  = name
  override def theDescription: String           = description
  override def theTags: Seq[String]             = tags
  override def theMetadata: Map[String, String] = metadata
}

object WafRuleset {

  val format: Format[WafRuleset] = new Format[WafRuleset] {
    override def writes(o: WafRuleset): JsValue = o.location.jsonWithKey ++ Json.obj(
      "id"          -> o.id,
      "name"        -> o.name,
      "description" -> o.description,
      "metadata"    -> o.metadata,
      "tags"        -> JsArray(o.tags.map(JsString.apply)),
      "enabled"     -> o.enabled,
      "rules"       -> o.rules
    )
    override def reads(json: JsValue): JsResult[WafRuleset] = Try {
      WafRuleset(
        location = otoroshi.models.EntityLocation.readFromKey(json),
        id = json.select("id").asString,
        name = json.select("name").asString,
        description = json.select("description").asOpt[String].getOrElse(""),
        metadata = json.select("metadata").asOpt[Map[String, String]].getOrElse(Map.empty),
        tags = json.select("tags").asOpt[Seq[String]].getOrElse(Seq.empty),
        enabled = json.select("enabled").asOpt[Boolean].getOrElse(true),
        rules = json.select("rules").asOpt[Seq[String]].getOrElse(Seq.empty)
      )
    } match {
      case Failure(ex)    => JsError(ex.getMessage)
      case Success(value) => JsSuccess(value)
    }
  }

  def resource(env: Env, datastores: WafExtensionDatastores, states: WafExtensionState): Resource = {
    Resource(
      "WafRuleset",
      "waf-rulesets",
      "waf-ruleset",
      "waf.extensions.cloud-apim.com",
      ResourceVersion("v1", true, false, true),
      GenericResourceAccessApiWithState[WafRuleset](
        format = WafRuleset.format,
        clazz = classOf[WafRuleset],
        keyf = id => datastores.wafRulesetDatastore.key(id),
        extractIdf = c => datastores.wafRulesetDatastore.extractId(c),
        extractIdJsonf = json => json.select("id").asString,
        idFieldNamef = () => "id",
        tmpl = (_, _, _) => {
          WafRuleset(
            id = IdGenerator.namedId("waf-ruleset", env),
            name = "WAF ruleset",
            description = "A reusable body of SecLang rules",
            rules = Seq("@import_preset crs")
          ).json
        },
        canRead = true,
        canCreate = true,
        canUpdate = true,
        canDelete = true,
        canBulk = true,
        stateAll = () => states.allRulesets(),
        stateOne = id => states.ruleset(id),
        stateUpdate = values => states.updateRulesets(values)
      )
    )
  }
}

trait WafRulesetDatastore extends BasicStore[WafRuleset]

class KvWafRulesetDatastore(extensionId: AdminExtensionId, redisCli: RedisLike, _env: Env)
    extends WafRulesetDatastore
    with RedisLikeStore[WafRuleset] {
  override def fmt: Format[WafRuleset]              = WafRuleset.format
  override def redisLike(using env: Env): RedisLike = redisCli
  override def key(id: String): String              =
    s"${_env.storageRoot}:extensions:${extensionId.cleanup}:wafrulesets:$id"
  override def extractId(value: WafRuleset): String = value.id
}

/**
 * The rules a config actually runs, and what went missing on the way.
 *
 * A reference that resolves to nothing is the dangerous case: the config still compiles, still
 * runs, and quietly protects less than it says it does. It is reported rather than swallowed.
 */
final case class ComposedRules(
    rules: Seq[String],
    missing: Seq[String],
    disabled: Seq[String],
    /** CRS settings were configured, but nothing in the composition imports CRS to read them. */
    crsIgnored: Boolean = false
) {
  def complete: Boolean = missing.isEmpty && disabled.isEmpty && !crsIgnored
  def json: JsValue     = Json.obj(
    "count"       -> rules.size,
    "missing"     -> missing,
    "disabled"    -> disabled,
    "crs_ignored" -> crsIgnored
  )
}

object WafRuleComposition {

  /**
   * Referenced rulesets in the order they are listed, then the config's own inline rules.
   *
   * Order is not cosmetic in SecLang: `@import_preset crs` has to be read before the rules that
   * refer to what it defines, and an exclusion has to come after the rule it excludes. Listing
   * order is therefore the composition order, and inline rules land last — which is what makes
   * "a shared baseline plus this route's exceptions" the natural arrangement.
   *
   * The one thing that comes before the presets is the generated CRS preamble, for the opposite
   * reason: it has to win against defaults the preset would otherwise set for itself.
   */
  /** `@import_preset crs`, whitespace and case allowed for. */
  private val crsImport = """(?im)^\s*@import_preset\s+crs\s*$""".r

  def importsCrs(rules: Seq[String]): Boolean = rules.exists(r => crsImport.findFirstIn(r).isDefined)

  def compose(config: CloudApimWafConfig, resolve: String => Option[WafRuleset]): ComposedRules = {
    val missing  = Seq.newBuilder[String]
    val disabled = Seq.newBuilder[String]
    val body     = Seq.newBuilder[String]
    config.rulesets.foreach { ref =>
      resolve(ref) match {
        case None                        => missing += ref
        case Some(rs) if !rs.enabled     => disabled += ref
        case Some(rs)                    => body ++= rs.rules
      }
    }
    body ++= config.rules
    val composed = body.result()

    // the dials only mean something to CRS. Emitting them into a configuration that never imports it
    // would write a rule that sets variables nothing reads — and would take an id in someone else's
    // program to do it. So the composition is scanned first, and the mistake is reported rather than
    // compiled into a no-op
    val wantsCrs = config.crs.nonEmpty
    val hasCrs   = importsCrs(composed)
    val preamble = if (wantsCrs && hasCrs) CrsSettings.preamble(config.crs) else Seq.empty

    // ahead of everything: the initialisation rules inside the preset only apply their defaults to
    // variables nobody has set, so being first is what makes them take
    ComposedRules(preamble ++ composed, missing.result(), disabled.result(), crsIgnored = wantsCrs && !hasCrs)
  }
}
