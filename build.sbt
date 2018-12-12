import Dependencies._

resolvers += "Sonatype Releases" at "https://oss.sonatype.org/content/repositories/releases/"

def baseProject(id: String): Project = {
  Project(id, file(id))
    .settings(commonSettings: _*)
}

val scalastyleCfgFile     = "project/scalastyle-config.xml"
val scalastyleTestCfgFile = "project/scalastyle-test-config.xml"

lazy val root: Project = Project("root", file("."))
  .settings(
    // This is just a project to aggregate modules, nothing to compile or to check scalastyle for.
    unmanagedSourceDirectories in Compile := Seq(),
    unmanagedSourceDirectories in Test := Seq(),
    scalastyle := {},
    scalastyle in Test := {},
  )
  .aggregate(app, `app-debug`, flow, util, serde, crypto, protocol)

def mainProject(id: String): Project = baseProject(id)
  .settings(
    Compile / scalastyleConfig := root.base / scalastyleCfgFile,
    Test    / scalastyleConfig := root.base / scalastyleTestCfgFile
  )
  .enablePlugins(JavaAppPackaging)
  .dependsOn(flow)

lazy val app = mainProject("app")

lazy val `app-debug` = mainProject("app-debug")
  .settings(
    libraryDependencies ++= Seq(
      metrics,
      `metrics-jmx`
    ),
    coverageEnabled := false
  )

def subProject(path: String): Project = {
  baseProject(path)
    .settings(
      Compile / scalastyleConfig := root.base / scalastyleCfgFile,
      Test    / scalastyleConfig := root.base / scalastyleTestCfgFile
    )
}

lazy val crypto = subProject("crypto")
  .dependsOn(util % "test->test;compile->compile", serde)
  .settings(
    libraryDependencies ++= Seq(
      curve25519
    )
  )

lazy val flow = subProject("flow")
  .settings(
    libraryDependencies ++= Seq(
      akka,
      `akka-slf4j`,
      `akka-http`,
      `akka-stream`,
      bcprov,
      `circe-parser`,
      `scala-logging`,
      `scala-reflect`(scalaVersion.value),
      logback
    )
  ).dependsOn(util % "test->test;compile->compile", serde, crypto, protocol % "test->test;compile->compile")

lazy val protocol = subProject("protocol")
  .dependsOn(util % "test->test;compile->compile", serde, crypto)
  .settings(
    libraryDependencies ++= Seq(
      `circe-parser`
    )
  )

lazy val serde = subProject("serde")
  .settings(
    Compile / sourceGenerators += (sourceManaged in Compile).map(Boilerplate.genSrc).taskValue,
    Test    / sourceGenerators += (sourceManaged in Test   ).map(Boilerplate.genTest).taskValue
  )
  .dependsOn(util % "test->test;compile->compile")

lazy val util = subProject("util")
  .settings(
    libraryDependencies ++= Seq(
      akka,
      bcprov,
      `scala-reflect`(scalaVersion.value)
    )
  )

val commonSettings = Seq(
  organization := "org.alephium",
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
  run / javaOptions += "-Xmx4g -XX:+UseG1GC",
  scalafmtOnCompile := true,
  (compile in Compile) := {
    val result = (compile in Compile).value
    scalastyle.in(Compile).toTask("").value
    result
  },
  libraryDependencies ++= Seq(
    akkatest,
    scalacheck,
    scalatest,
  )
)
