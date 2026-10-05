package com.cloud.apim.otoroshi.extensions.waf.security

import play.api.libs.json.{JsValue, Json}

import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}

/**
 * How many requests one node holds at once (BEH-5).
 *
 * A held request costs no thread, it costs a connection: the caller's, which is the point, and
 * ours, which is the risk. Past the bound a tarpit lets the request through at once and a slow
 * refusal refuses at once, because being worn down by our own defence is exactly what it exists to
 * prevent.
 */
final class TarpitGate(val maxHeld: Int) {

  private val held = new AtomicInteger(0)

  def current: Int = held.get()

  /** A slot to hold one request in, if one is left. It must be released once the hold is over. */
  def acquire(): Option[TarpitSlot] = {
    var taken = false
    var going = true
    while (going) {
      val now = held.get()
      if (now >= maxHeld) going = false
      else if (held.compareAndSet(now, now + 1)) {
        taken = true
        going = false
      }
    }
    if (taken) Some(new TarpitSlot(() => { held.decrementAndGet(); () })) else None
  }

  def status: JsValue = Json.obj("held" -> held.get(), "max_held" -> maxHeld)
}

/** One held request. Releasing it twice releases it once. */
final class TarpitSlot(onRelease: () => Unit) {
  private val released = new AtomicBoolean(false)
  def release(): Unit  = if (released.compareAndSet(false, true)) onRelease()
}
