import Dependencies._

ThisBuild / scalaVersion     := "3.8.4"
ThisBuild / version          := "1.0.0-dev"
ThisBuild / organization     := "com.cloud-apim"
ThisBuild / organizationName := "Cloud-APIM"

lazy val jackson = Seq(
  ExclusionRule("com.fasterxml.jackson"),
  ExclusionRule("com.fasterxml.jackson.core", "jackson-core"),
  ExclusionRule("com.fasterxml.jackson.core", "jackson-databind"),
  ExclusionRule("com.fasterxml.jackson.core", "jackson-datatypes"),
  ExclusionRule("com.fasterxml.jackson.core", "jackson-annotations"),
)

lazy val slf4j = Seq(
  ExclusionRule("org.slf4j"),
  ExclusionRule("ch.qos.logback")
)

// everything listed here is already on the otoroshi classpath at runtime, in the exact same
// version, so the copy provided by otoroshi wins and we avoid shipping duplicate classes in
// the assembly. all of it is scala 3 on both sides now.
lazy val other = Seq(
  ExclusionRule("org.scala-lang"),
  // play-json moved from com.typesafe.play to org.playframework with play 3
  ExclusionRule("org.playframework"),
  ExclusionRule("io.opentelemetry"),
  ExclusionRule("com.github.blemale"),
  ExclusionRule("com.comcast"),
  ExclusionRule("org.typelevel"),
  ExclusionRule("org.apache.commons", "commons-text"),
)

lazy val all = jackson ++ slf4j ++ other

lazy val root = (project in file("."))
  .settings(
    name := "otoroshi-waf-extension",
    scalacOptions ++= Seq(
      "-deprecation",
      "-feature",
      "-unchecked",
      "-Wunused:all",
      // the wasm4s "bundle" jar (transitive, provided) vendors an older scala 3 stdlib where
      // `scala.caps` is an object while scala-library 3.8.4 declares it as a package. otoroshi
      // itself silences the very same warning.
      "-Wconf:msg=package scala contains object and package with same name:s",
    ),
    libraryDependencies ++= Seq(
      "fr.maif" %% "otoroshi" % "18.0.0-preview2" % "provided",
      "com.cloud-apim" %% "seclang-engine-coreruleset" % "2.1.0" excludeAll (all: _*),
      munit % Test
    ),
    assembly / test  := {},
    assembly / assemblyJarName := "otoroshi-waf-extension-assembly_3-dev.jar",
    // otoroshi already provides the exact same scala3-library at runtime, no need to ship a
    // second copy of the whole stdlib in the plugin jar
    assembly / assemblyPackageScala / assembleArtifact := false,
    assembly / assemblyMergeStrategy := {
      case PathList(ps @ _*) if ps.contains("module-info.class") => MergeStrategy.first
      case x =>
        val oldStrategy = (assembly / assemblyMergeStrategy).value
        oldStrategy(x)
    }
  )
