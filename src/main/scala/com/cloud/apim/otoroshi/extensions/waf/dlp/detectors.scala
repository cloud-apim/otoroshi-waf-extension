package com.cloud.apim.otoroshi.extensions.waf.dlp

import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.regex.{Matcher, Pattern}
import scala.collection.mutable.ArrayBuffer
import scala.util.Try

/** What a detector does with what it finds (DLP-2). */
sealed trait DlpAction {
  def name: String
}

object DlpAction {
  case object Off   extends DlpAction { val name = "off"   }
  case object Log   extends DlpAction { val name = "log"   }
  case object Mask  extends DlpAction { val name = "mask"  }
  case object Block extends DlpAction { val name = "block" }

  val all: Seq[DlpAction] = Seq(Off, Log, Mask, Block)

  def parse(raw: String): Option[DlpAction] = all.find(_.name.equalsIgnoreCase(raw.trim))
}

/**
 * Where a finding is, and what of it gets masked.
 *
 * `[start, end)` is the value itself, `[maskFrom, maskTo)` the part that is rewritten, and the first
 * `keepStart` and last `keepEnd` letters and digits of that part are left as they are, so that a
 * masked card still says which card it was: `4111 **** **** 1111`.
 */
final case class Span(start: Int, end: Int, maskFrom: Int, maskTo: Int, keepStart: Int, keepEnd: Int)

object Span {
  def of(m: Matcher, keepStart: Int = 0, keepEnd: Int = 0): Span = Span(m.start, m.end, m.start, m.end, keepStart, keepEnd)
}

/**
 * One kind of sensitive value, and how to recognise it (DLP-1).
 *
 * A pattern alone flags too much: any sixteen digits look like a card. Each detector checks what its
 * pattern found the way the issuer would (a Luhn check and an issuer prefix for a card, mod 97 for an
 * IBAN, the key of a NIR, a header that decodes for a JWT) and only then calls it a finding.
 *
 * Every pattern is bounded, and [[maxLength]] is the longest value it can match: the scanner holds
 * back that much of a stream, so that no value is ever cut in two by a chunk boundary.
 *
 * Patterns run over bytes read as ISO-8859-1, one character per byte, so a position in the text is a
 * position in the body and masking never changes its length. Every pattern is ASCII, so the bytes of
 * a multi-byte UTF-8 character never match anything.
 *
 * A pattern is never searched for across a whole body: ten of them, each tried at every position,
 * would cost more than the response is worth. [[candidates]] says, with a tight loop or an `indexOf`,
 * where a value may start, and the pattern is only tried there, anchored.
 */
abstract class Detector(
    val id: String,
    val label: String,
    val family: String,
    val defaultAction: DlpAction,
    val maxLength: Int,
    regex: String
) {
  val pattern: Pattern = Pattern.compile(regex)

  /** A volume detector counts what it finds and only reports past a threshold. Nothing to mask. */
  def volume: Boolean = false

  /**
   * Every position in `[from, until)` where a value may start, in increasing order.
   *
   * It may say too much, the pattern decides; it must never say too little, a position it skips is a
   * value nobody sees.
   */
  def candidates(text: String, from: Int, until: Int): Iterator[Int]

  /** The finding in what the pattern matched, if there is one. */
  def validate(text: String, m: Matcher): Option[Span] = Some(Span.of(m))
}

object Detectors {

  // what may surround a token: these characters would make it a different, longer token
  private val tokenChar = "A-Za-z0-9_-"

  object Card
      extends Detector(
        "card",
        "Payment card numbers",
        "payment",
        DlpAction.Mask,
        40,
        """(?<![0-9])(?:[0-9][ -]?){12,18}[0-9](?![0-9])"""
      ) {
    override def validate(text: String, m: Matcher): Option[Span] = {
      val raw    = text.substring(m.start, m.end)
      val digits = raw.filter(c => c >= '0' && c <= '9')
      // one kind of separator or none: 4111 1111-1111 1111 is two numbers that happen to touch
      val separators = raw.filterNot(c => c >= '0' && c <= '9').toSet
      Option.when(separators.size <= 1 && Validators.issuer(digits) && Validators.luhn(digits))(Span.of(m, 4, 4))
    }

    // the start of a run of digits and separators long enough to hold thirteen digits
    override def candidates(text: String, from: Int, until: Int): Iterator[Int] =
      Candidates.where(text, from, until) { i =>
        Candidates.digit(text.charAt(i)) && (i == 0 || !Candidates.digit(text.charAt(i - 1))) && {
          var j = i
          while (j < text.length && j - i < 13 && (Candidates.digit(text.charAt(j)) || text.charAt(j) == ' ' || text.charAt(j) == '-')) j += 1
          j - i >= 13
        }
      }
  }

  object Iban
      extends Detector(
        "iban",
        "IBANs",
        "banking",
        DlpAction.Mask,
        50,
        """(?<![A-Za-z0-9])[A-Z]{2}[0-9]{2}(?:(?: [A-Z0-9]{4}){2,7}(?: [A-Z0-9]{1,4})?|[A-Z0-9]{11,30})(?![A-Za-z0-9])"""
      ) {
    override def validate(text: String, m: Matcher): Option[Span] =
      Validators.ibanLengths.get(text.substring(m.start, m.start + 2)).flatMap { length =>
        // a spaced IBAN can run on into the word after it: its country says where it really ends
        val compact = new StringBuilder
        var i       = m.start
        while (i < m.end && compact.length < length) {
          val c = text.charAt(i)
          if (c != ' ') compact.append(c)
          i += 1
        }
        val whole = compact.length == length && (i == m.end || text.charAt(i) == ' ')
        Option.when(whole && Validators.mod97(compact.toString) == 1)(Span(m.start, i, m.start, i, 4, 4))
      }

    // two capitals and two digits, after something that is not a letter or a digit
    override def candidates(text: String, from: Int, until: Int): Iterator[Int] =
      Candidates.where(text, from, until) { i =>
        i + 3 < text.length && Candidates.upper(text.charAt(i)) && Candidates.upper(text.charAt(i + 1)) &&
        Candidates.digit(text.charAt(i + 2)) && Candidates.digit(text.charAt(i + 3)) &&
        (i == 0 || !Candidates.alnum(text.charAt(i - 1)))
      }
  }

  object FrenchNir
      extends Detector(
        "fr_nir",
        "French social security numbers",
        "identity",
        DlpAction.Mask,
        24,
        """(?<![0-9A-Za-z])[1-478] ?[0-9]{2} ?(?:0[1-9]|[1-9][0-9]) ?(?:[0-9]{2}|2[ABab]) ?[0-9]{3} ?[0-9]{3} ?[0-9]{2}(?![0-9A-Za-z])"""
      ) {
    override def validate(text: String, m: Matcher): Option[Span] = {
      val compact = text.substring(m.start, m.end).filter(_ != ' ').toUpperCase
      Option.when(Validators.nirKey(compact))(Span.of(m, 0, 4))
    }

    override def candidates(text: String, from: Int, until: Int): Iterator[Int] =
      Candidates.where(text, from, until) { i =>
        val c = text.charAt(i)
        (c == '1' || c == '2' || c == '3' || c == '4' || c == '7' || c == '8') && (i == 0 || !Candidates.alnum(text.charAt(i - 1))) && {
          // fifteen characters, digits but for Corsica's A or B, spaces between groups
          var j      = i
          var counted = 0
          while (j < text.length && counted < 15 && (Candidates.digit(text.charAt(j)) || text.charAt(j) == ' ' || "ABab".indexOf(text.charAt(j)) >= 0)) {
            if (text.charAt(j) != ' ') counted += 1
            j += 1
          }
          counted >= 15
        }
      }
  }

  object UsSsn
      extends Detector(
        "us_ssn",
        "US social security numbers",
        "identity",
        DlpAction.Mask,
        12,
        // the areas and groups the SSA never issues are left out by the pattern itself
        """(?<![0-9-])(?!000|666|9[0-9]{2})[0-9]{3}-(?!00)[0-9]{2}-(?!0000)[0-9]{4}(?![0-9-])"""
      ) {
    override def validate(text: String, m: Matcher): Option[Span] = Some(Span.of(m, 0, 4))

    override def candidates(text: String, from: Int, until: Int): Iterator[Int] =
      Candidates.where(text, from, until) { i =>
        i + 3 < text.length && text.charAt(i + 3) == '-' && Candidates.digit(text.charAt(i)) &&
        (i == 0 || (!Candidates.digit(text.charAt(i - 1)) && text.charAt(i - 1) != '-'))
      }
  }

  object PrivateKey
      extends Detector(
        "private_key",
        "Private keys",
        "secret",
        DlpAction.Block,
        16700,
        // an unterminated block is masked over a key's worth of what follows, rather than not at all
        """-----BEGIN[A-Z ]{0,40}PRIVATE KEY(?: BLOCK)?-----(?:[\s\S]{0,16384}?-----END[A-Z ]{0,40}PRIVATE KEY(?: BLOCK)?-----|[\s\S]{0,4096})"""
      ) {
    override def validate(text: String, m: Matcher): Option[Span] = {
      // the BEGIN and END lines stay: they say what was there without being it
      val from = text.indexOf("-----", m.start + 10) + 5
      val end  = text.lastIndexOf("-----END", m.end)
      val to   = if (end >= from) end else m.end
      Some(Span(m.start, m.end, from, to, 0, 0))
    }

    override def candidates(text: String, from: Int, until: Int): Iterator[Int] =
      Candidates.anchored(text, from, until, Seq("-----BEGIN"))
  }

  object Jwt
      extends Detector(
        "jwt",
        "JSON Web Tokens",
        "secret",
        // a login endpoint returns them on purpose: reported, not masked, unless told otherwise
        DlpAction.Log,
        10300,
        s"""(?<![$tokenChar])eyJ[A-Za-z0-9_-]{5,1024}\\.eyJ[A-Za-z0-9_-]{5,8192}\\.[A-Za-z0-9_-]{0,1024}(?![$tokenChar])"""
      ) {
    override def validate(text: String, m: Matcher): Option[Span] = {
      val dot    = text.indexOf('.', m.start)
      val header = Try(new String(Base64.getUrlDecoder.decode(text.substring(m.start, dot)), StandardCharsets.UTF_8)).getOrElse("")
      // the header stays, it only names the algorithm: the claims and the signature go
      Option.when(header.contains("\"alg\""))(Span(m.start, m.end, dot + 1, m.end, 0, 0))
    }

    override def candidates(text: String, from: Int, until: Int): Iterator[Int] =
      Candidates.anchored(text, from, until, Seq("eyJ"))
  }

  /** A token whose prefix says what it is: the prefix stays, the secret goes. Every value starts with one of `anchors`. */
  abstract class Prefixed(id: String, label: String, maxLength: Int, anchors: Seq[String], regex: String)
      extends Detector(id, label, "secret", DlpAction.Mask, maxLength, s"(?<![$tokenChar])(?:$regex)(?![$tokenChar])") {
    override def candidates(text: String, from: Int, until: Int): Iterator[Int] =
      Candidates.anchored(text, from, until, anchors)

    override def validate(text: String, m: Matcher): Option[Span] = {
      val prefix = (1 to m.groupCount).iterator.map(m.group).find(_ != null).getOrElse("")
      Some(Span.of(m, prefix.count(_.isLetterOrDigit), 0))
    }
  }

  object CloudKey
      extends Prefixed(
        "cloud_key",
        "Cloud provider keys",
        110,
        Seq("AKIA", "ASIA", "ABIA", "ACCA", "AIza", "AccountKey="),
        """(AKIA|ASIA|ABIA|ACCA)[A-Z0-9]{16}|(AIza)[0-9A-Za-z_-]{35}|(AccountKey=)[A-Za-z0-9+/]{86}=="""
      )

  object ServiceToken
      extends Prefixed(
        "service_token",
        "Service tokens",
        270,
        Seq("ghp_", "gho_", "ghu_", "ghs_", "ghr_", "github_pat_", "glpat-", "xoxb-", "xoxa-", "xoxp-", "xoxr-", "xoxs-", "sk_live_", "rk_live_"),
        """(gh[pousr]_)[A-Za-z0-9]{36}|(github_pat_)[A-Za-z0-9_]{82}|(glpat-)[A-Za-z0-9_-]{20}|(xox[baprs]-)[A-Za-z0-9-]{10,250}|((?:sk|rk)_live_)[A-Za-z0-9]{24,247}"""
      )

  object LlmKey
      extends Prefixed(
        "llm_key",
        "LLM provider keys",
        530,
        Seq("sk-"),
        """(sk-ant-(?:api|admin)[0-9]{2}-)[A-Za-z0-9_-]{80,120}|(sk-(?:proj-|svcacct-|admin-)?)[A-Za-z0-9_-]{16,250}T3BlbkFJ[A-Za-z0-9_-]{16,250}"""
      )

  object EmailBulk
      extends Detector(
        "email_bulk",
        "Email addresses in bulk",
        "contact",
        DlpAction.Log,
        700,
        """(?<![A-Za-z0-9._%+-])[A-Za-z0-9._%+-]{1,64}@[A-Za-z0-9-]{1,63}(?:\.[A-Za-z0-9-]{1,63}){0,8}\.[A-Za-z]{2,24}(?![A-Za-z0-9-])"""
      ) {
    // one address is a profile, a thousand are an export
    override def volume: Boolean = true

    // every address has an @, and starts where the run of local-part characters before it starts
    override def candidates(text: String, from: Int, until: Int): Iterator[Int] =
      Candidates.anchored(text, from, text.length, Seq("@")).map { at =>
        var start = at
        while (start > 0 && at - start <= 64 && Candidates.local(text.charAt(start - 1))) start -= 1
        if (start == at || at - start > 64) -1 else start
      }.filter(s => s >= from).takeWhile(_ < until)
  }

  val all: Seq[Detector] = Seq(Card, Iban, FrenchNir, UsSsn, PrivateKey, CloudKey, ServiceToken, LlmKey, Jwt, EmailBulk)

  def byId(id: String): Option[Detector] = all.find(_.id == id)
}

/** Cheap ways to find where a value may start. */
object Candidates {

  def digit(c: Char): Boolean = c >= '0' && c <= '9'
  def upper(c: Char): Boolean = c >= 'A' && c <= 'Z'
  def alnum(c: Char): Boolean = digit(c) || upper(c) || (c >= 'a' && c <= 'z')
  def local(c: Char): Boolean = alnum(c) || c == '.' || c == '_' || c == '%' || c == '+' || c == '-'

  /** The positions of `[from, until)` that `accept` takes. */
  def where(text: String, from: Int, until: Int)(accept: Int => Boolean): Iterator[Int] = new Iterator[Int] {
    private var i                     = from
    private def seek(): Unit          = while (i < until && !accept(i)) i += 1
    seek()
    def hasNext: Boolean              = i < until
    def next(): Int                   = {
      val at = i
      i += 1
      seek()
      at
    }
  }

  /** The positions of `[from, until)` where one of `anchors` starts, each found with `indexOf`. */
  def anchored(text: String, from: Int, until: Int, anchors: Seq[String]): Iterator[Int] = new Iterator[Int] {
    private val at                      = anchors.map(a => text.indexOf(a, from)).toArray
    private def nearest: Int            = {
      var best = -1
      var k    = 0
      while (k < at.length) {
        if (at(k) >= 0 && at(k) < until && (best < 0 || at(k) < best)) best = at(k)
        k += 1
      }
      best
    }
    def hasNext: Boolean                = nearest >= 0
    def next(): Int                     = {
      val pos = nearest
      var k   = 0
      while (k < at.length) {
        if (at(k) == pos) at(k) = text.indexOf(anchors(k), pos + 1)
        k += 1
      }
      pos
    }
  }
}

object Validators {

  def luhn(digits: String): Boolean = {
    var sum    = 0
    var double = false
    var i      = digits.length - 1
    while (i >= 0) {
      var d = digits.charAt(i) - '0'
      if (double) {
        d *= 2
        if (d > 9) d -= 9
      }
      sum += d
      double = !double
      i -= 1
    }
    digits.nonEmpty && sum % 10 == 0
  }

  /**
   * Whether the number is one a card network issues, at a length it issues.
   *
   * Luhn alone passes one random number in ten. The issuer prefix is what keeps millisecond
   * timestamps, which start with a 1, and most identifiers out.
   */
  def issuer(d: String): Boolean = {
    val n = d.length
    if (n < 13 || n > 19) false
    else {
      def p(k: Int): Int = d.substring(0, k).toInt
      (d.charAt(0) == '4' && (n == 13 || n == 16 || n == 19)) ||
      (p(2) >= 51 && p(2) <= 55 && n == 16) ||
      (p(4) >= 2221 && p(4) <= 2720 && n == 16) ||
      ((p(2) == 34 || p(2) == 37) && n == 15) ||
      ((p(4) == 6011 || (p(3) >= 644 && p(3) <= 649) || p(2) == 65) && n >= 16) ||
      (p(4) >= 3528 && p(4) <= 3589 && n >= 16) ||
      (((p(3) >= 300 && p(3) <= 305) || p(2) == 36 || p(2) == 38 || p(2) == 39) && n >= 14) ||
      (p(2) == 62 && n >= 16) ||
      Set(5018, 5020, 5038, 5893, 6304, 6759, 6761, 6762, 6763).contains(p(4))
    }
  }

  /** IBAN lengths by country, from the SWIFT registry. */
  val ibanLengths: Map[String, Int] = Map(
    "AD" -> 24, "AE" -> 23, "AL" -> 28, "AT" -> 20, "AZ" -> 28, "BA" -> 20, "BE" -> 16, "BG" -> 22, "BH" -> 22,
    "BR" -> 29, "BY" -> 28, "CH" -> 21, "CR" -> 22, "CY" -> 28, "CZ" -> 24, "DE" -> 22, "DK" -> 18, "DO" -> 28,
    "EE" -> 20, "EG" -> 29, "ES" -> 24, "FI" -> 18, "FO" -> 18, "FR" -> 27, "GB" -> 22, "GE" -> 22, "GI" -> 23,
    "GL" -> 18, "GR" -> 27, "GT" -> 28, "HR" -> 21, "HU" -> 28, "IE" -> 22, "IL" -> 23, "IQ" -> 23, "IS" -> 26,
    "IT" -> 27, "JO" -> 30, "KW" -> 30, "KZ" -> 20, "LB" -> 28, "LC" -> 32, "LI" -> 21, "LT" -> 20, "LU" -> 20,
    "LV" -> 21, "MC" -> 27, "MD" -> 24, "ME" -> 22, "MK" -> 19, "MR" -> 27, "MT" -> 31, "MU" -> 30, "NL" -> 18,
    "NO" -> 15, "PK" -> 24, "PL" -> 28, "PS" -> 29, "PT" -> 25, "QA" -> 29, "RO" -> 24, "RS" -> 22, "SA" -> 24,
    "SC" -> 31, "SE" -> 24, "SI" -> 19, "SK" -> 24, "SM" -> 27, "ST" -> 25, "SV" -> 28, "TL" -> 23, "TN" -> 24,
    "TR" -> 26, "UA" -> 29, "VA" -> 22, "VG" -> 24, "XK" -> 20
  )

  /** The ISO 13616 check: the country and check digits moved to the end, letters as numbers, mod 97. */
  def mod97(iban: String): Int = {
    var acc = 0
    (iban.drop(4) + iban.take(4)).foreach { c =>
      if (c >= '0' && c <= '9') acc = (acc * 10 + (c - '0')) % 97
      else if (c >= 'A' && c <= 'Z') acc = (acc * 100 + (c - 'A' + 10)) % 97
      else acc = -1000
    }
    if (acc < 0) -1 else acc
  }

  /**
   * The NIR key: 97 minus the first thirteen digits modulo 97.
   *
   * Corsica's departments are written 2A and 2B, and count as 19 and 18 in the computation.
   */
  def nirKey(compact: String): Boolean =
    compact.length == 15 && Try {
      val number = compact.take(13).replace("2A", "19").replace("2B", "18").toLong
      compact.drop(13).toInt == 97 - (number % 97).toInt
    }.getOrElse(false)
}

/**
 * Rewrites a finding in place, so the body keeps its length and its syntax.
 *
 * Only letters and digits change, to `*`, so every quote, bracket and separator of a JSON or XML
 * document stays where it was. An escape sequence (`\n`, `A`) and a character reference
 * (`&amp;`) are never touched: their letters are syntax, and rewriting them would break the very
 * document the masking is meant to keep valid. A value written as a bare JSON number is masked with
 * zeros instead, which leaves a number where one was expected.
 */
object Masking {

  private def alnum(b: Byte): Boolean = (b >= '0' && b <= '9') || (b >= 'A' && b <= 'Z') || (b >= 'a' && b <= 'z')

  private def digit(b: Byte): Boolean = b >= '0' && b <= '9'

  private def blank(b: Byte): Boolean = b == ' ' || b == '\t' || b == '\r' || b == '\n'

  /** Masks `span` in `bytes`. Returns how many characters changed. */
  def apply(bytes: Array[Byte], span: Span): Int = {
    val positions = ArrayBuffer.empty[Int]
    // an escape that opened just before the span still covers its first characters
    var i         = math.max(0, span.maskFrom - 6)
    while (i < span.maskTo) {
      val b = bytes(i)
      if (b == '\\' && i + 1 < bytes.length) {
        i += (if (bytes(i + 1) == 'u') 6 else 2)
      } else if (b == '&' && entityEnd(bytes, i) > 0) {
        i = entityEnd(bytes, i)
      } else {
        if (i >= span.maskFrom && alnum(b)) positions += i
        i += 1
      }
    }
    val keep    = span.keepStart + span.keepEnd
    val changed = if (keep >= positions.size) positions else positions.slice(span.keepStart, positions.size - span.keepEnd)
    val mask    = if (bareNumber(bytes, span.maskFrom, span.maskTo)) '0'.toByte else '*'.toByte
    changed.foreach(p => bytes(p) = mask)
    changed.size
  }

  /** Where a character reference opening at `at` ends, or -1 when `&` is just an ampersand. */
  private def entityEnd(bytes: Array[Byte], at: Int): Int = {
    var j = at + 1
    while (j < bytes.length && j < at + 12 && (alnum(bytes(j)) || bytes(j) == '#')) j += 1
    if (j < bytes.length && j > at + 1 && bytes(j) == ';') j + 1 else -1
  }

  /** Whether `[from, to)` is all digits sitting where a JSON value goes, unquoted. */
  private def bareNumber(bytes: Array[Byte], from: Int, to: Int): Boolean =
    from < to && (from until to).forall(i => digit(bytes(i))) && {
      var before = from - 1
      while (before >= 0 && blank(bytes(before))) before -= 1
      var after  = to
      while (after < bytes.length && blank(bytes(after))) after += 1
      before >= 0 && (bytes(before) == ':' || bytes(before) == ',' || bytes(before) == '[') &&
      (after >= bytes.length || bytes(after) == ',' || bytes(after) == '}' || bytes(after) == ']')
    }
}
