import Dependencies._

Global / cancelable := true // Allow cancelation of forked task without killing SBT

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
  .aggregate(app, `app-debug`, `app-server`, flow, protocol)

def mainProject(id: String): Project =
  project(id)
    .enablePlugins(JavaAppPackaging)
    .dependsOn(`app-server`)

def project(path: String): Project = {
  baseProject(path)
    .settings(
      Compile / scalastyleConfig := root.base / scalastyleCfgFile,
      Test / scalastyleConfig := root.base / scalastyleTestCfgFile
    )
}

lazy val app = mainProject("app")

lazy val `app-debug` = mainProject("app-debug")
  .settings(
    libraryDependencies ++= Seq(
      metrics,
      `metrics-jmx`
    ),
    coverageEnabled := false
  )

lazy val `app-server` = project("app-server")
  .dependsOn(flow, flow % "test->test")
  .settings(
    libraryDependencies ++= Seq(
      `alephium-rpc`,
      `alephium-util` % "test" classifier "tests",
      akkahttptest,
      akkastreamtest
    )
  )

lazy val benchmark = mainProject("benchmark")
  .enablePlugins(JmhPlugin)
  .settings(scalacOptions += "-Xdisable-assertions")

lazy val flow = project("flow")
  .settings(
    libraryDependencies ++= Seq(
      `alephium-crypto`,
      `alephium-serde`,
      `alephium-util` % "test" classifier "tests",
      akka,
      logback,
      rocksdb,
      `scala-logging`
    )
  )
  .dependsOn(protocol % "test->test;compile->compile")

lazy val protocol = project("protocol")
  .settings(
    libraryDependencies ++= Seq(
      `alephium-crypto`,
      `alephium-serde`,
      `alephium-util` % "test" classifier "tests"
    )
  )

val commonSettings = Seq(
  organization := "org.alephium",
  version := "0.3.0-SNAPSHOT",
  scalaVersion := "2.12.9",
  parallelExecution in Test := false,
  scalacOptions ++= Seq(
//    "-Xdisable-assertions", // TODO: use this properly
    "-deprecation",
    "-encoding",
    "utf-8",
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
  wartremoverErrors in (Compile, compile) := Warts.allBut(wartsCompileExcludes: _*),
  wartremoverErrors in (Test, test) := Warts.allBut(wartsTestExcludes: _*),
  fork := true,
  Test / scalacOptions += "-Xcheckinit",
  Test / javaOptions += "-Xss2m",
  Test / envVars += "ALEPHIUM_ENV" -> "test",
  run / javaOptions += "-Xmx4g",
  libraryDependencies ++= Seq(
    akkatest,
    scalacheck,
    scalatest,
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
  Wart.Equals
)

val wartsTestExcludes = wartsCompileExcludes ++ Seq(
  Wart.PublicInference,
  Wart.TraversableOps,
  Wart.OptionPartial
)
