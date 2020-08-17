import Dependencies._

Global / cancelable := true // Allow cancellation of forked task without killing SBT

resolvers += "Sonatype Releases" at "https://oss.sonatype.org/content/repositories/releases/"

def baseProject(id: String): Project = {
  Project(id, file(id))
    .settings(commonSettings: _*)
}

val scalastyleCfgFile     = "project/scalastyle-config.xml"
val scalastyleTestCfgFile = "project/scalastyle-test-config.xml"

lazy val root: Project = Project("alephium-scala-blockflow", file("."))
  .settings(commonSettings: _*)
  .settings(
    // This is just a project to aggregate modules, nothing to compile or to check scalastyle for.
    unmanagedSourceDirectories := Seq(),
    scalastyle := {},
    scalastyle in Test := {}
  )
  .aggregate(macros, util, serde, io, crypto, rpc, `app-server`, benchmark, flow, protocol)

def mainProject(id: String): Project =
  project(id).enablePlugins(JavaAppPackaging).dependsOn(flow)

def project(path: String): Project = {
  baseProject(path)
    .configs(IntegrationTest extend Test)
    .settings(
      Defaults.itSettings,
      inConfig(IntegrationTest)(org.scalafmt.sbt.ScalafmtPlugin.scalafmtConfigSettings),
      Compile / scalastyleConfig := root.base / scalastyleCfgFile,
      Test / scalastyleConfig := root.base / scalastyleTestCfgFile,
      inConfig(IntegrationTest)(ScalastylePlugin.rawScalastyleSettings()),
      IntegrationTest / scalastyleConfig := root.base / scalastyleTestCfgFile,
      IntegrationTest / scalastyleTarget := target.value / "scalastyle-it-results.xml",
      IntegrationTest / scalastyleSources := (IntegrationTest / unmanagedSourceDirectories).value
    )
}

lazy val macros = project("macros")
  .settings(
    libraryDependencies += `scala-reflect`(scalaVersion.value),
    wartremoverErrors in (Compile, compile) := Warts.allBut(
      wartsCompileExcludes :+ Wart.AsInstanceOf: _*)
  )

lazy val util = project("util")
  .dependsOn(macros)
  .settings(
    scalacOptions -= "-Xlint:nonlocal-return",
    publishArtifact in Test := true,
    libraryDependencies ++= Seq(
      akka,
      `akka-slf4j`,
      bcprov,
      `scala-reflect`(scalaVersion.value)
    )
  )

lazy val serde = project("serde")
  .settings(
    Compile / sourceGenerators += (sourceManaged in Compile).map(Boilerplate.genSrc).taskValue,
    Test / sourceGenerators += (sourceManaged in Test).map(Boilerplate.genTest).taskValue
  )
  .dependsOn(util % "test->test;compile->compile")

lazy val crypto = project("crypto")
  .dependsOn(util % "test->test;compile->compile", serde)

lazy val io = project("io")
  .dependsOn(util % "test->test;compile->compile", serde, crypto)
  .settings(libraryDependencies += rocksdb)

lazy val rpc = project("rpc")
  .settings(
    libraryDependencies ++= Seq(
      `akka-http`,
      `akka-http-circe`,
      `akka-stream`,
      `circe-parser`,
      `circe-generic`,
      `scala-logging`,
      `akka-test`,
      `akka-http-test`
    )
  )
  .dependsOn(util % "test->test;compile->compile")

lazy val `app-server` = mainProject("app-server")
  .dependsOn(rpc, util % "it,test->test", flow, flow % "it,test->test")
  .settings(
    libraryDependencies ++= Seq(
      `akka-http-cors`,
      `akka-http-test`,
      `akka-stream-test`,
      `tapir-core`,
      `tapir-circe`,
      `tapir-akka`,
      `tapir-openapi`,
      `tapir-openapi-circe`,
      `tapir-swagger-ui`,
    )
  )

lazy val benchmark = project("benchmark")
  .enablePlugins(JmhPlugin)
  .dependsOn(flow)
  .settings(scalacOptions += "-Xdisable-assertions")

lazy val flow = project("flow")
  .dependsOn(crypto, io, serde, util % "test->test")
  .settings(
    libraryDependencies ++= Seq(
      akka,
      `akka-slf4j`,
      logback,
      `scala-logging`
    )
  )
  .dependsOn(protocol % "test->test;compile->compile")

lazy val protocol = project("protocol")
  .dependsOn(crypto, io % "compile->compile;test->test", serde, util % "test->test")
  .settings(
    libraryDependencies ++= Seq(
      fastparse,
      pureconfig
    )
  )

val commonSettings = Seq(
  organization := "org.alephium",
  version := "0.2.1-SNAPSHOT",
  scalaVersion := "2.13.3",
  parallelExecution in Test := false,
  scalacOptions ++= Seq(
//    "-Xdisable-assertions", // TODO: use this properly
    "-deprecation",
    "-encoding",
    "utf-8",
    "-explaintypes",
    "-feature",
    "-unchecked",
    "-Xsource:3",
    "-Xfatal-warnings",
    "-Xlint:adapted-args",
    "-Xlint:constant",
    "-Xlint:delayedinit-select",
    "-Xlint:doc-detached",
    "-Xlint:inaccessible",
    "-Xlint:infer-any",
    "-Xlint:missing-interpolator",
    "-Xlint:nullary-unit",
    "-Xlint:option-implicit",
    "-Xlint:package-object-classes",
    "-Xlint:poly-implicit-overload",
    "-Xlint:private-shadow",
    "-Xlint:stars-align",
    "-Xlint:type-parameter-shadow",
    "-Xlint:nonlocal-return",
    "-Ywarn-dead-code",
    "-Ywarn-extra-implicit",
    "-Ywarn-numeric-widen",
    "-Ywarn-unused:implicits",
    "-Ywarn-unused:imports",
    "-Ywarn-unused:locals",
    "-Ywarn-unused:params",
    "-Ywarn-unused:patvars",
    "-Ywarn-unused:privates",
    "-Ywarn-value-discard"
  ),
  wartremoverErrors in (Compile, compile) := Warts.allBut(wartsCompileExcludes: _*),
  wartremoverErrors in (Test, test) := Warts.allBut(wartsTestExcludes: _*),
  wartremoverErrors in (IntegrationTest, test) := Warts.allBut(wartsTestExcludes: _*),
  fork := true,
  Test / scalacOptions += "-Xcheckinit",
  Test / javaOptions += "-Xss2m",
  Test / envVars += "ALEPHIUM_ENV"            -> "test",
  IntegrationTest / envVars += "ALEPHIUM_ENV" -> "it",
  run / javaOptions += "-Xmx4g",
  libraryDependencies ++= Seq(
    `akka-test`,
    scalacheck,
    scalatest,
    scalatestplus
  )
)

val wartsCompileExcludes = Seq(
  Wart.MutableDataStructures,
  Wart.Var,
  Wart.Overloading,
  Wart.ImplicitParameter,
  Wart.NonUnitStatements,
  Wart.Nothing,
  Wart.Null, // Partially covered by scalastyle, only use _ inside actors
  Wart.Return, // Covered by scalastyle
  Wart.Any,
  Wart.Throw,
  Wart.Equals,
  Wart.StringPlusAny,
  Wart.While
)

val wartsTestExcludes = wartsCompileExcludes ++ Seq(
  Wart.PublicInference,
  Wart.TraversableOps,
  Wart.OptionPartial
)
