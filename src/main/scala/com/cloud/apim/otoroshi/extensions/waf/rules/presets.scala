package com.cloud.apim.otoroshi.extensions.waf.rules

import com.cloud.apim.seclang.model.SecLangPreset
import com.cloud.apim.seclang.scaladsl.coreruleset.EmbeddedCRSPreset

/**
 * The Core Rule Set as the extension runs it.
 *
 * The embedded preset keys its data files by their path in the jar (`/rules/sql-errors.data`)
 * while its rules name them bare (`@pmFromFile sql-errors.data`), and seclang-engine up to 2.5.0
 * looks a file up by exactly that name. So every CRS rule reading a data file never matched:
 * scanner user agents, LFI paths, shell commands, PHP functions, SQL and PHP error leaks. Each file
 * is reachable by its bare name here as well, unless two share it. From seclang-engine 2.5.1 the
 * engine resolves bare names itself, and this changes nothing.
 */
object CrsPreset {

  lazy val embedded: SecLangPreset = {
    val crs = EmbeddedCRSPreset.embedded
    crs.copy(files = withBareNames(crs.files))
  }

  def withBareNames(files: Map[String, String]): Map[String, String] = {
    val bare = files.toSeq
      .groupBy { case (path, _) => path.split('/').last }
      .collect { case (name, Seq((_, content))) if !files.contains(name) => name -> content }
    files ++ bare
  }
}
