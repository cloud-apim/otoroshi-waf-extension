package com.cloud.apim.otoroshi.extensions.waf.challenge

import com.cloud.apim.otoroshi.extensions.waf.entities.ChallengeProvider
import play.api.libs.json.*

/**
 * The page a challenged caller is served.
 *
 * Self-contained for the `pow` kind: no external script, no font, no analytics, nothing that leaves
 * the page. The solver runs in a Web Worker so the tab stays responsive while it grinds, and the
 * whole exchange is one POST back to the same URL — which avoids needing a separate verify endpoint
 * on the protected host, and keeps the cookie same-origin by construction.
 */
object Interstitial {

  val submissionHeader: String = "X-CloudApim-Challenge"

  def render(provider: ChallengeProvider, challenge: IssuedChallenge): String = {
    val payload = Json.stringify(challenge.payload)
    val title   = escape(provider.title)
    val message = escape(provider.message)
    val vendorScript =
      if (provider.isVendor && provider.widgetScriptUrl.trim.nonEmpty)
        s"""<script src="${escapeAttr(provider.widgetScriptUrl)}" async defer></script>"""
      else ""

    s"""<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="robots" content="noindex, nofollow">
<title>$title</title>
<style>
  :root {
    --bg: #f4f5f3; --surface: #fff; --text: #16191a; --muted: #5d6360;
    --border: #dfe1dc; --accent: #1f5e4e;
  }
  @media (prefers-color-scheme: dark) {
    :root { --bg: #131513; --surface: #1b1f1d; --text: #e9e8e1; --muted: #a3aaa6;
            --border: #2e322f; --accent: #6fbfa3; }
  }
  * { box-sizing: border-box; }
  body { margin: 0; min-height: 100vh; display: grid; place-items: center; background: var(--bg);
         color: var(--text); font-family: system-ui, -apple-system, "Segoe UI", Roboto, sans-serif; }
  .card { background: var(--surface); border: 1px solid var(--border); border-radius: 8px;
          padding: 1.75rem 2rem; max-width: 30rem; width: calc(100% - 2rem); }
  h1 { font-size: 1.25rem; margin: 0 0 .6rem; }
  p { color: var(--muted); line-height: 1.55; margin: 0 0 1.2rem; }
  .bar { height: 6px; background: var(--border); border-radius: 3px; overflow: hidden; }
  .bar > i { display: block; height: 100%; width: 0%; background: var(--accent); transition: width .25s; }
  .status { font-size: .85rem; color: var(--muted); margin-top: .7rem; }
  noscript { color: var(--muted); }
</style>
</head>
<body>
<div class="card">
  <h1>$title</h1>
  <p>$message</p>
  <div class="bar" role="progressbar" aria-live="polite" aria-label="Verification progress"><i id="bar"></i></div>
  <div class="status" id="status">Starting…</div>
  <noscript><p>This check needs JavaScript. Enable it, or contact the site owner for another way in.</p></noscript>
</div>
$vendorScript
<script>
(function () {
  var payload = $payload;
  var bar = document.getElementById('bar');
  var status = document.getElementById('status');
  var header = ${Json.stringify(JsString(submissionHeader))};

  function submit(body) {
    status.textContent = 'Verifying…';
    var headers = { 'Content-Type': 'application/json' };
    headers[header] = JSON.stringify(body);
    fetch(window.location.href, { method: 'POST', headers: headers, credentials: 'same-origin', body: '{}' })
      .then(function (r) {
        if (r.ok || r.status === 204) { window.location.reload(); }
        else { status.textContent = 'Verification failed. Reloading to try again…'; setTimeout(function () { window.location.reload(); }, 2500); }
      })
      .catch(function () { status.textContent = 'Network problem. Retrying…'; setTimeout(function () { window.location.reload(); }, 2500); });
  }

  if (payload.kind === 'vendor') {
    status.textContent = 'Waiting for the verification widget…';
    var holder = document.createElement('div');
    holder.innerHTML = payload.widget;
    document.querySelector('.card').insertBefore(holder, document.querySelector('.bar'));
    // vendors expose their answer through a named field rather than a common callback api,
    // so watch for the field to be filled rather than guessing at each vendor's javascript
    var tries = 0;
    var timer = setInterval(function () {
      tries++;
      bar.style.width = Math.min(95, tries) + '%';
      var el = document.querySelector('[name="' + payload.response_field + '"]');
      if (el && el.value) { clearInterval(timer); bar.style.width = '100%'; submit({ token: el.value }); }
      if (tries > 300) { clearInterval(timer); status.textContent = 'The verification widget did not respond.'; }
    }, 400);
    return;
  }

  var workerSource = [
    'self.onmessage = function (e) {',
    '  var challenge = e.data.challenge, difficulty = e.data.difficulty, nonce = 0;',
    '  function hex(buf) { var a = Array.prototype.slice.call(new Uint8Array(buf));',
    '    return a.map(function (b) { return b.toString(16).padStart(2, "0"); }).join(""); }',
    '  function zeros(h) { var bits = 0;',
    '    for (var i = 0; i < h.length; i++) { var n = parseInt(h[i], 16);',
    '      for (var j = 3; j >= 0; j--) { if (((n >> j) & 1) === 0) bits++; else return bits; } }',
    '    return bits; }',
    '  function step() {',
    '    var enc = new TextEncoder();',
    '    crypto.subtle.digest("SHA-256", enc.encode(challenge + ":" + nonce)).then(function (buf) {',
    '      var h = hex(buf);',
    '      if (zeros(h) >= difficulty) { postMessage({ done: true, nonce: String(nonce) }); return; }',
    '      nonce++;',
    '      if (nonce % 500 === 0) postMessage({ done: false, attempts: nonce });',
    '      step();',
    '    });',
    '  }',
    '  step();',
    '};'
  ].join('\\n');

  var worker = new Worker(URL.createObjectURL(new Blob([workerSource], { type: 'application/javascript' })));
  var expected = Math.pow(2, payload.difficulty);
  worker.onmessage = function (ev) {
    if (ev.data.done) {
      bar.style.width = '100%';
      submit({ challenge: payload.challenge, nonce: ev.data.nonce });
    } else {
      bar.style.width = Math.min(95, Math.floor((ev.data.attempts / expected) * 100)) + '%';
      status.textContent = 'Working… ' + ev.data.attempts.toLocaleString() + ' attempts';
    }
  };
  worker.postMessage({ challenge: payload.challenge, difficulty: payload.difficulty });
})();
</script>
</body>
</html>"""
  }

  private def escape(v: String): String =
    v.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

  private def escapeAttr(v: String): String = escape(v).replace("\"", "&quot;")
}
