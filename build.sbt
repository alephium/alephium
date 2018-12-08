import Dependencies._

resolvers += "Sonatype Releases" at "https://oss.sonatype.org/content/repositories/releases/"

def baseProject(path: String, id: String): Project = {
  Project(id, file(path))
    .settings(commonSettings: _*)
    .settings(libraryDependencies ++= dependencies)
}

val scalastyleCfgFile     = "project/scalastyle-config.xml"
val scalastyleTestCfgFile = "project/scalastyle-test-config.xml"

lazy val root = baseProject(".", "root")
  .settings(
    Compile / scalastyleConfig := baseDirectory.value / scalastyleCfgFile,
    Test    / scalastyleConfig := baseDirectory.value / scalastyleTestCfgFile
  )
  .dependsOn(util % "test->test;compile->compile", serde, crypto, protocol % "test->test;compile->compile")
  .aggregate(util, serde, crypto, protocol)

def subProject(path: String): Project = {
  baseProject(path, path)
    .settings(
      Compile / scalastyleConfig := root.base / scalastyleCfgFile,
      Test    / scalastyleConfig := root.base / scalastyleTestCfgFile
    )
}

lazy val util = subProject("util")

lazy val serde = subProject("serde")
  .settings(
    Compile / sourceGenerators += (sourceManaged in Compile).map(Boilerplate.genSrc).taskValue,
    Test    / sourceGenerators += (sourceManaged in Test   ).map(Boilerplate.genTest).taskValue
  )
  .dependsOn(util % "test->test;compile->compile")

lazy val crypto = subProject("crypto")
  .dependsOn(util % "test->test;compile->compile", serde)

lazy val protocol = subProject("protocol")
  .dependsOn(util % "test->test;compile->compile", serde, crypto)

val commonSettings = Seq(
  organization := "org.alephium",
  name := "alephium",
  version := "0.1.0-SNAPSHOT",
  scalaVersion := "2.12.5",
  parallelExecution in Test := false,
  scalacOptions := Seq(
    "-deprecation",
    "-encoding", "utf-8",
    "-explaintypes",
    "-feature",
    "-unchecked",
    "-Xfatal-warnings",
    "-Xlint:adapted-args",
    "-Xlint:by-name-right-associative",
    "-Xlint:constant",
    "-Xlint:delayedinit-select",
    "-Xlint:doc-detached",
    "-Xlint:inaccessible",
    "-Xlint:infer-any",
    "-Xlint:missing-interpolator",
    "-Xlint:nullary-override",
    "-Xlint:nullary-unit",
    "-Xlint:option-implicit",
    "-Xlint:package-object-classes",
    "-Xlint:poly-implicit-overload",
    "-Xlint:private-shadow",
    "-Xlint:stars-align",
    "-Xlint:type-parameter-shadow",
    "-Xlint:unsound-match",
    "-Yno-adapted-args",
    "-Ypartial-unification",
    "-Ywarn-dead-code",
    "-Ywarn-extra-implicit",
    "-Ywarn-inaccessible",
    "-Ywarn-infer-any",
    "-Ywarn-nullary-override",
    "-Ywarn-nullary-unit",
    "-Ywarn-numeric-widen",
    "-Ywarn-unused:implicits",
    "-Ywarn-unused:imports",
    "-Ywarn-unused:locals",
    "-Ywarn-unused:params",
    "-Ywarn-unused:patvars",
    "-Ywarn-unused:privates",
    "-Ywarn-value-discard"
  ),
  Test / scalacOptions += "-Xcheckinit",
  fork := true,
  run / javaOptions += "-Xmx4g"
)

val dependencies = Seq(
  akka,
  akkatest,
  `akka-slf4j`,
  bcprov,
  `scala-logging`,
  logback,
  scalatest,
  scalacheck,
  curve25519,
  `circe-parser`
)
