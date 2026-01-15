import Dependencies._

ThisBuild / scalaVersion     := "2.12.13"
ThisBuild / version          := "1.0.0-dev"
ThisBuild / organization     := "com.cloud-apim"
ThisBuild / organizationName := "Cloud-APIM"

lazy val excludesSlf4j = Seq(
  ExclusionRule(organization = "org.slf4j"),
)

lazy val root = (project in file("."))
  .settings(
    name := "otoroshi-waf",
    assembly / test  := {},
    assembly / assemblyJarName := "otoroshi-waf-assembly_2.12-dev.jar",
    libraryDependencies ++= Seq(
      "fr.maif" %% "otoroshi" % "17.11.0" % "provided",
      "com.cloud-apim" %% "seclang-engine-coreruleset" % "1.1.0",
      munit % Test
    )
  )