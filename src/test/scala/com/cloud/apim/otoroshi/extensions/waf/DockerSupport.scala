package com.cloud.apim.otoroshi.extensions.waf

/**
 * Shared by every container-backed suite here.
 *
 * docker-java negotiates API 1.32 by default, and daemons from Docker 25 onward refuse anything
 * below 1.40 with a bare 400 — which surfaces as "could not find a valid Docker environment" even
 * though the socket is perfectly reachable.
 *
 * 1.41 shipped with Docker 20.10 and every daemon since accepts it. Only applied when the machine
 * has not already pinned a version, so an explicit local setting always wins.
 *
 * It has to run before anything touches `DockerClientFactory`, which is why suites call it as their
 * first statement rather than in `beforeAll`.
 */
object DockerSupport {

  def pinApiVersion(): Unit = {
    val configured =
      Option(System.getenv("DOCKER_API_VERSION")).exists(_.trim.nonEmpty) ||
        Option(System.getProperty("api.version")).exists(_.trim.nonEmpty)
    if (!configured) System.setProperty("api.version", "1.41")
  }

  def available(): Boolean = {
    pinApiVersion()
    try org.testcontainers.DockerClientFactory.instance().isDockerAvailable
    catch { case _: Throwable => false }
  }
}
