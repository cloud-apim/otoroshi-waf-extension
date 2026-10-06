package otoroshi_plugins.com.cloud.apim.otoroshi.extensions.waf.plugins

import com.cloud.apim.otoroshi.extensions.waf.body.{BodyEncoding, BodyReader, StreamDecoder}
import com.cloud.apim.otoroshi.extensions.waf.security.{ThreatAction, ThreatBus, ThreatDecision, ThreatSignal}
import com.cloud.apim.otoroshi.extensions.waf.upload.*
import org.apache.pekko.stream.Materializer
import org.apache.pekko.stream.scaladsl.{Flow, Sink, Source}
import org.apache.pekko.util.ByteString
import otoroshi.env.Env
import otoroshi.next.plugins.api.*
import otoroshi.utils.http.RequestImplicits.EnhancedRequestHeader
import otoroshi.utils.syntax.implicits.*
import play.api.Logger
import play.api.libs.json.*
import play.api.mvc.{Result, Results}

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

/**
 * What the upload guard accepts (WAF-4).
 *
 * `body_limit` is how much of the body is read before anything is forwarded: a file refused within
 * it gets a proper status, a file refused past it cuts the upload on its way to the backend.
 */
final case class CloudApimUploadGuardConfig(
    mode: String = "enforce",
    allowedExtensions: Seq[String] = Seq.empty,
    deniedExtensions: Seq[String] = UploadPolicy.defaultDeniedExtensions,
    allowedTypes: Seq[String] = Seq.empty,
    deniedTypes: Seq[String] = UploadPolicy.defaultDeniedKinds,
    checkMismatch: Boolean = true,
    maxFiles: Int = 100,
    maxFileSize: Long = 0L,
    archiveMaxDepth: Int = 3,
    archiveMaxEntries: Int = 10000,
    archiveMaxExpandedSize: Long = 256L * 1024L * 1024L,
    archiveMaxRatio: Long = 100L,
    unreadableArchiveAction: String = "reject",
    checkArchiveEntryExtensions: Boolean = false,
    bodyLimit: Long = 1024L * 1024L,
    // WAF-5: the malware scanner files go to, and what a scan that cannot be made does
    scanner: Option[String] = None,
    scanFailureAction: String = "reject"
) extends NgPluginConfig {
  override def json: JsValue = CloudApimUploadGuardConfig.format.writes(this)

  def enforces: Boolean = !mode.trim.equalsIgnoreCase("monitor")

  def rejectsScanFailures: Boolean = !scanFailureAction.trim.equalsIgnoreCase("allow")

  private def normalised(exts: Seq[String]): Set[String] = exts.map(_.trim.stripPrefix(".").toLowerCase).filter(_.nonEmpty).toSet

  def policy: UploadPolicy = UploadPolicy(
    allowedExtensions = normalised(allowedExtensions),
    deniedExtensions = normalised(deniedExtensions),
    allowedKinds = allowedTypes.map(_.trim.toLowerCase).filter(_.nonEmpty).toSet,
    deniedKinds = deniedTypes.map(_.trim.toLowerCase).filter(_.nonEmpty).toSet,
    checkMismatch = checkMismatch,
    maxFiles = maxFiles,
    maxFileSize = maxFileSize,
    archives = ArchiveLimits(
      maxDepth = archiveMaxDepth,
      maxEntries = archiveMaxEntries,
      maxExpandedSize = archiveMaxExpandedSize,
      maxRatio = archiveMaxRatio,
      unreadable = unreadableArchiveAction,
      checkEntryExtensions = checkArchiveEntryExtensions
    )
  )
}

object CloudApimUploadGuardConfig {

  val default: CloudApimUploadGuardConfig = CloudApimUploadGuardConfig()

  def modeOf(raw: Option[String]): String =
    raw.map(_.trim.toLowerCase).filter(Set("enforce", "monitor")).getOrElse("enforce")

  private def strings(json: JsValue, name: String): Option[Seq[String]] =
    json.select(name).asOpt[Seq[String]].map(_.map(_.trim).filter(_.nonEmpty))

  val format: Format[CloudApimUploadGuardConfig] = new Format[CloudApimUploadGuardConfig] {
    override def writes(o: CloudApimUploadGuardConfig): JsValue = Json.obj(
      "mode"                           -> o.mode,
      "allowed_extensions"             -> o.allowedExtensions,
      "denied_extensions"              -> o.deniedExtensions,
      "allowed_types"                  -> o.allowedTypes,
      "denied_types"                   -> o.deniedTypes,
      "check_mismatch"                 -> o.checkMismatch,
      "max_files"                      -> o.maxFiles,
      "max_file_size"                  -> o.maxFileSize,
      "archive_max_depth"              -> o.archiveMaxDepth,
      "archive_max_entries"            -> o.archiveMaxEntries,
      "archive_max_expanded_size"      -> o.archiveMaxExpandedSize,
      "archive_max_ratio"              -> o.archiveMaxRatio,
      "unreadable_archive_action"      -> o.unreadableArchiveAction,
      "check_archive_entry_extensions" -> o.checkArchiveEntryExtensions,
      "body_limit"                     -> o.bodyLimit,
      "scanner"                        -> o.scanner,
      "scan_failure_action"            -> o.scanFailureAction
    )
    override def reads(json: JsValue): JsResult[CloudApimUploadGuardConfig] = Try {
      val d = CloudApimUploadGuardConfig.default
      CloudApimUploadGuardConfig(
        mode = modeOf(json.select("mode").asOpt[String]),
        allowedExtensions = strings(json, "allowed_extensions").getOrElse(d.allowedExtensions),
        deniedExtensions = strings(json, "denied_extensions").getOrElse(d.deniedExtensions),
        allowedTypes = strings(json, "allowed_types").getOrElse(d.allowedTypes),
        deniedTypes = strings(json, "denied_types").getOrElse(d.deniedTypes),
        checkMismatch = json.select("check_mismatch").asOpt[Boolean].getOrElse(d.checkMismatch),
        maxFiles = json.select("max_files").asOpt[Int].getOrElse(d.maxFiles),
        maxFileSize = json.select("max_file_size").asOpt[Long].getOrElse(d.maxFileSize),
        archiveMaxDepth = json.select("archive_max_depth").asOpt[Int].filter(_ > 0).getOrElse(d.archiveMaxDepth),
        archiveMaxEntries = json.select("archive_max_entries").asOpt[Int].getOrElse(d.archiveMaxEntries),
        archiveMaxExpandedSize = json.select("archive_max_expanded_size").asOpt[Long].getOrElse(d.archiveMaxExpandedSize),
        archiveMaxRatio = json.select("archive_max_ratio").asOpt[Long].getOrElse(d.archiveMaxRatio),
        unreadableArchiveAction =
          json.select("unreadable_archive_action").asOpt[String].map(_.trim.toLowerCase).filter(Set("reject", "allow")).getOrElse("reject"),
        checkArchiveEntryExtensions = json.select("check_archive_entry_extensions").asOpt[Boolean].getOrElse(d.checkArchiveEntryExtensions),
        bodyLimit = json.select("body_limit").asOpt[Long].filter(_ > 0L).getOrElse(d.bodyLimit),
        scanner = json.select("scanner").asOpt[String].map(_.trim).filter(_.nonEmpty),
        scanFailureAction =
          json.select("scan_failure_action").asOpt[String].map(_.trim.toLowerCase).filter(Set("reject", "allow")).getOrElse("reject")
      )
    } match {
      case Success(value) => JsSuccess(value)
      case Failure(err)   => JsError(err.getMessage)
    }
  }

  val configFlow: Seq[String] = Seq(
    "mode",
    "allowed_extensions",
    "denied_extensions",
    "allowed_types",
    "denied_types",
    "check_mismatch",
    "max_files",
    "max_file_size",
    "archive_max_depth",
    "archive_max_entries",
    "archive_max_expanded_size",
    "archive_max_ratio",
    "unreadable_archive_action",
    "check_archive_entry_extensions",
    "scanner",
    "scan_failure_action",
    "body_limit"
  )

  private def number(label: String, help: String, suffix: Option[String] = None) = Json.obj(
    "type"  -> "number",
    "label" -> label,
    "props" -> (Json.obj("help" -> help) ++ suffix.fold(Json.obj())(s => Json.obj("suffix" -> s)))
  )

  private def array(label: String, help: String) = Json.obj("type" -> "array", "label" -> label, "props" -> Json.obj("help" -> help))

  val configSchema: JsObject = Json.obj(
    "mode"                           -> Json.obj(
      "type"  -> "select",
      "label" -> "Mode",
      "props" -> Json.obj(
        "help"    -> "'monitor' reports what would be refused and lets every upload through",
        "options" -> Json.arr(
          Json.obj("label" -> "Enforce", "value" -> "enforce"),
          Json.obj("label" -> "Monitor", "value" -> "monitor")
        )
      )
    ),
    "allowed_extensions"             -> array("Allowed extensions", "When not empty, the only extensions accepted, without the dot"),
    "denied_extensions"              -> array("Denied extensions", "Never accepted, as the last extension or hidden before it"),
    "allowed_types"                  -> array("Allowed kinds", s"When not empty, the only kinds of content accepted: ${Magic.kinds.mkString(", ")}"),
    "denied_types"                   -> array("Denied kinds", "Never accepted, whatever the file is called"),
    "check_mismatch"                 -> Json.obj(
      "type"  -> "bool",
      "label" -> "Check type mismatches",
      "props" -> Json.obj("help" -> "Refuse a file whose content is of another family than its name or declared type say")
    ),
    "max_files"                      -> number("Max files", "Files in one request. 0 for no limit"),
    "max_file_size"                  -> number("Max file size", "Bytes per file. 0 for no limit", Some("bytes")),
    "archive_max_depth"              -> number("Archive nesting", "Archives inside archives, the uploaded one counting as 1"),
    "archive_max_entries"            -> number("Archive entries", "Entries across every archive of the request. 0 for no limit"),
    "archive_max_expanded_size"      -> number("Archive expanded size", "Bytes every archive of the request may expand to. 0 for no limit", Some("bytes")),
    "archive_max_ratio"              -> number("Archive ratio", "How many times the archives may expand, judged past 1 MiB. 0 for no limit"),
    "unreadable_archive_action"      -> Json.obj(
      "type"  -> "select",
      "label" -> "Unreadable archives",
      "props" -> Json.obj(
        "help"    -> "Encrypted entries, compression methods nothing here reads, 7z, rar, xz, zstd and bzip2 archives",
        "options" -> Json.arr(Json.obj("label" -> "Reject", "value" -> "reject"), Json.obj("label" -> "Allow", "value" -> "allow"))
      )
    ),
    "check_archive_entry_extensions" -> Json.obj(
      "type"  -> "bool",
      "label" -> "Check archive entry extensions",
      "props" -> Json.obj("help" -> "Hold the names inside archives to the denied extensions too. Entries escaping their directory are always refused")
    ),
    "scanner"                        -> Json.obj(
      "type"  -> "select",
      "label" -> "Malware scanner",
      "props" -> Json.obj(
        "help"               -> "Every uploaded file also goes to this antivirus. Empty means no malware scan",
        "optionsFrom"        -> "/bo/api/proxy/apis/waf.extensions.cloud-apim.com/v1/malware-scanners",
        "optionsTransformer" -> Json.obj("label" -> "name", "value" -> "id")
      )
    ),
    "scan_failure_action"            -> Json.obj(
      "type"  -> "select",
      "label" -> "When a scan fails",
      "props" -> Json.obj(
        "help"    -> "A scanner down, timing out, or a file too large for it: refuse the upload, or let it through unscanned",
        "options" -> Json.arr(Json.obj("label" -> "Reject", "value" -> "reject"), Json.obj("label" -> "Allow", "value" -> "allow"))
      )
    ),
    "body_limit"                     -> number("Status decision limit", "Bytes read before the upload is forwarded. Past them a refusal cuts the upload", Some("bytes"))
  )
}

/**
 * Judges uploaded files by what they are, not by what they say they are (WAF-4).
 *
 * Every file of a `multipart/form-data` body is read as it streams to the backend: its name the way
 * the server storing it will read it, its first bytes for what it really is, and its contents when
 * it is an archive, entry by entry, inflated, against a budget and a nesting depth. A script named
 * `.php.jpg`, an executable named `.png`, a GIF that is also PHP, a zip bomb or an entry escaping
 * its directory are refused, or reported in monitor mode.
 */
class CloudApimUploadGuard extends NgRequestTransformer {

  private val logger = Logger("cloud-apim-waf-uploads")

  override def steps: Seq[NgStep]                          = Seq(NgStep.TransformRequest)
  override def categories: Seq[NgPluginCategory]           = Seq(NgPluginCategory.AccessControl, CloudApimSecuritySuite.category)
  override def visibility: NgPluginVisibility              = NgPluginVisibility.NgUserLand
  override def multiInstance: Boolean                      = false
  override def core: Boolean                               = true
  override def name: String                                = "Cloud APIM Threat Protection - Upload guard"
  override def description: Option[String]                 =
    "Refuses uploaded files by what they are: disguised scripts and executables, polyglots, archive bombs and zip slips".some
  override def defaultConfigObject: Option[NgPluginConfig] = CloudApimUploadGuardConfig.default.some

  override def noJsForm: Boolean              = true
  override def configFlow: Seq[String]        = CloudApimUploadGuardConfig.configFlow
  override def configSchema: Option[JsObject] = CloudApimUploadGuardConfig.configSchema.some

  override def isTransformRequestAsync: Boolean  = true
  override def isTransformResponseAsync: Boolean = false
  override def transformsRequest: Boolean        = true
  override def transformsResponse: Boolean       = false
  override def transformsError: Boolean          = false

  override def transformRequest(
      ctx: NgTransformerRequestContext
  )(using env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, NgPluginHttpRequest]] = {
    val config  = ctx.cachedConfig(internalName)(CloudApimUploadGuardConfig.format).getOrElse(CloudApimUploadGuardConfig.default)
    val request = ctx.otoroshiRequest
    if (!ctx.request.theHasBody) request.rightf
    else
      UploadScanner.boundaryOf(request.contentType) match {
        case None                  => request.rightf
        case Some(Left(problem))   =>
          refuse(ctx, config, UploadViolation(UploadReason.Malformed, s"a multipart body with $problem"), request, None, () => drainAll(request))
        case Some(Right(boundary)) =>
          BodyEncoding.of(request.headers) match {
            case BodyEncoding.Undecodable(codings) =>
              refuse(ctx, config, UploadViolation(UploadReason.UndecodableBody, s"an upload encoded with $codings"), request, None, () => drainAll(request))
            case encoding                          =>
              val coding  = encoding match {
                case BodyEncoding.Decodable(c) => Some(c)
                case _                         => None
              }
              val scanner = new UploadScanner(boundary, config.policy.copy(scanning = scanSettings(config)))
              val reader  = new UploadReader(coding, scanner)
              BodyReader.prefix(request.body, config.bodyLimit).flatMap { prefix =>
                Try {
                  reader.feed(prefix.buffered)
                  if (!prefix.truncated) reader.finish()
                } match {
                  case Failure(e) =>
                    logger.error("could not read an upload, it goes through as it is", e)
                    reader.close()
                    Right(request.copy(body = prefix.resume)).vfuture
                  case Success(_) =>
                    scanner.violation match {
                      case Some(v)                  =>
                        reader.close()
                        refuse(ctx, config, v, request, Some(scanner), () => prefix.drain(), Some(prefix.resume))
                      case None if !prefix.truncated && scanner.scanned =>
                        // the whole upload is in hand: the scanner answers before anything is forwarded
                        scanner.scanVerdict().flatMap {
                          case Some(v) => refuse(ctx, config, v, request, Some(scanner), () => prefix.drain(), Some(prefix.resume))
                          case None    => Right(request.copy(body = prefix.resume)).vfuture
                        }
                      case None if !prefix.truncated =>
                        if (scanner.fileCount > 0) logger.debug(s"${scanner.fileCount} uploaded files accepted on ${ctx.route.id}")
                        Right(request.copy(body = prefix.resume)).vfuture
                      case None                     =>
                        Right(request.copy(body = prefix.resumeVia(tail(ctx, config, reader, scanner)))).vfuture
                    }
                }
              }
          }
      }
  }

  /**
   * The rest of the upload, read on its way to the backend.
   *
   * The status is already decided by then, so a file refused here cuts the upload before the chunk
   * that revealed it is forwarded: the backend gets a broken body rather than the file.
   */
  private def tail(ctx: NgTransformerRequestContext, config: CloudApimUploadGuardConfig, reader: UploadReader, scanner: UploadScanner)(using
      env: Env,
      ec: ExecutionContext
  ): Flow[ByteString, ByteString, ?] = {
    var reported = false
    def refuseIf(violation: Option[UploadViolation]): Unit = violation.foreach { v =>
      if (!reported) {
        reported = true
        report(ctx, config, v, Some(scanner), cut = true)
      }
      if (config.enforces) throw new UploadRefusedException(v.reason.name)
    }
    def check(): Unit = refuseIf(scanner.violation)
    // WAF-5: the scanner answers once a file is whole, so the last chunk waits for it: the backend
    // never receives a complete upload the scanner has not cleared
    val holds = config.scanner.isDefined
    val last  = scala.concurrent.Promise[Option[ByteString]]()
    val reading = Flow[ByteString]
      .statefulMap(() => Option.empty[ByteString])(
        { (held, chunk) =>
          if (!scanner.halted) reader.feed(chunk)
          check()
          if (holds) (Some(chunk), held.toList) else (None, chunk :: Nil)
        },
        { held =>
          if (!scanner.halted) reader.finish()
          check()
          last.trySuccess(held)
          None
        }
      )
      .mapConcat(identity)
    val ending: org.apache.pekko.stream.scaladsl.Source[ByteString, ?] =
      org.apache.pekko.stream.scaladsl.Source.futureSource(last.future.flatMap { held =>
        val verdict = if (scanner.scanned) scanner.scanVerdict() else Future.successful(None)
        verdict.map { v =>
          refuseIf(v)
          org.apache.pekko.stream.scaladsl.Source(held.toList)
        }
      })
    reading
      .concat(ending)
      .watchTermination() { (mat, done) =>
        done.onComplete(_ => reader.close())(ExecutionContext.parasitic)
        mat
      }
  }

  /** The malware scanner of this route, if it has one, as the scanner reads it. */
  private def scanSettings(config: CloudApimUploadGuardConfig)(using env: Env, ec: ExecutionContext, mat: Materializer): Option[ScanSettings] =
    config.scanner.map { ref =>
      ThreatSupport.module.flatMap(_.states.malwareScanner(ref)).filter(_.enabled) match {
        case Some(s) =>
          val client = s.client(using env.otoroshiActorSystem)
          ScanSettings(path => client.scan(path), s.maxFileSize, config.rejectsScanFailures)
        case None    =>
          ScanSettings(_ => Future.successful(ScanVerdict.Failed("unused")), 0L, config.rejectsScanFailures, Some(s"the malware scanner $ref does not exist or is disabled"))
      }
    }

  private def refuse(
      ctx: NgTransformerRequestContext,
      config: CloudApimUploadGuardConfig,
      v: UploadViolation,
      request: NgPluginHttpRequest,
      scanner: Option[UploadScanner],
      drain: () => Unit,
      resume: Option[Source[ByteString, ?]] = None
  )(using env: Env, ec: ExecutionContext): Future[Either[Result, NgPluginHttpRequest]] = {
    report(ctx, config, v, scanner, cut = false)
    if (config.enforces) {
      drain()
      Left(
        Results.Status(v.reason.status)(
          Json.obj(
            "error"     -> "upload_refused",
            "reason"    -> v.reason.name,
            // why a scan failed names the scanner's address: that is for the event, not the caller
            "detail"    -> (if (v.reason == UploadReason.ScanFailed) "the file could not be scanned" else v.detail),
            "reference" -> ctx.snowflake
          )
        )
      ).vfuture
    } else Right(resume.fold(request)(body => request.copy(body = body))).vfuture
  }

  // a refused request may still be uploading: what is neither forwarded nor read hangs the connection
  private def drainAll(request: NgPluginHttpRequest)(using mat: Materializer): Unit = { request.body.runWith(Sink.ignore); () }

  private def report(ctx: NgTransformerRequestContext, config: CloudApimUploadGuardConfig, v: UploadViolation, scanner: Option[UploadScanner], cut: Boolean)(
      using env: Env
  ): Unit =
    Try {
      val identity = ThreatSupport.identityOf(ctx.request, ctx.attrs)
      val what     = v.filename.fold(v.detail)(f => s"$f: ${v.detail}")
      // what only an attacker does weighs on the caller; the threat response reads it in monitor mode
      if (!cut) ThreatBus.contribute(ctx.attrs, identity, ThreatSignal(source = "upload", kind = v.reason.name, weight = v.reason.weight, tag = s"upload:${v.reason.name}", detail = Some(what)))
      ThreatSupport.module.foreach { mod =>
        val decision = ThreatDecision(
          action = if (config.enforces) ThreatAction.Deny else ThreatAction.Log,
          score = v.reason.weight,
          tier = None,
          dryRun = !config.enforces,
          reason = what
        )
        mod.record(
          category = "upload",
          identity = identity,
          decision = decision,
          tags = Seq(s"upload:${v.reason.name}"),
          signals = Json.arr(
            v.json.as[JsObject] ++ Json.obj(
              "cut"       -> cut,
              "reference" -> ctx.snowflake,
              "files"     -> JsArray(scanner.toSeq.flatMap(_.files.map(_.json)))
            )
          ),
          routeId = ctx.route.id.some,
          routeName = ctx.route.name.some,
          message = s"upload ${if (config.enforces) (if (cut) "cut" else "refused") else "let through"}: $what",
          ledgerWeight = if (config.enforces) v.reason.weight else 0
        )
      }
    }.failed.foreach(e => logger.error("could not report a refused upload", e))
}

/** An upload stopped on its way to the backend. */
final class UploadRefusedException(reason: String) extends Exception(s"upload refused: $reason")

/**
 * The body as the scanner must read it: decoded when it is compressed, a piece at a time.
 *
 * Play already decodes gzip and deflate request bodies, so mostly brotli reaches this. A body that
 * claims an encoding but turns out not to be in it from its very first bytes was decoded upstream
 * already, and is read as it is.
 */
private[plugins] final class UploadReader(coding: Option[String], scanner: UploadScanner) {

  private var decoder: Option[StreamDecoder] = coding.map(c => new StreamDecoder(c))
  private var started                        = false

  def feed(chunk: ByteString): Unit = decoder match {
    case None    => scanner.feed(chunk)
    case Some(d) =>
      var produced = false
      d.feed(chunk) { piece =>
        produced = true
        scanner.feed(piece)
        !scanner.halted
      }
      if (d.corrupt.isDefined) {
        d.close()
        decoder = None
        if (!started && !produced) scanner.feed(chunk)
        else scanner.fail(UploadViolation(UploadReason.UndecodableBody, s"an upload that does not decode: ${d.corrupt.get}"))
      }
      started = true
  }

  def finish(): Unit = {
    scanner.finish()
    close()
  }

  def close(): Unit = {
    decoder.foreach(_.close())
    scanner.close()
  }
}
