import Dependencies._

ThisBuild / scalaVersion     := "2.12.13"
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

lazy val other = Seq(
  ExclusionRule("org.scala-lang"),
  ExclusionRule("com.typesafe.play"),
  ExclusionRule("io.opentelemetry"),
  ExclusionRule("com.github.blemale"),
  ExclusionRule("org.apache.commons", "commons-text"),
)

lazy val all = jackson ++ slf4j ++ other

lazy val root = (project in file("."))
  .settings(
    name := "otoroshi-waf-extension",
    assembly / test  := {},
    libraryDependencies ++= Seq(
      "fr.maif" %% "otoroshi" % "17.11.0" % "provided",
      "com.cloud-apim" %% "seclang-engine-coreruleset" % "1.2.0" excludeAll(all: _*),
      munit % Test
    ),
    assembly / test  := {},
    assembly / assemblyJarName := "otoroshi-waf-extension-assembly_2.12-dev.jar",
    assembly / assemblyMergeStrategy := {
      case PathList(ps @ _*) if ps.contains("module-info.class") => MergeStrategy.first
      case x =>
        val oldStrategy = (assembly / assemblyMergeStrategy).value
        oldStrategy(x)
    }
  )