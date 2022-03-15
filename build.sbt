import sbt._
import sbt.Keys._
import Dependencies._

Global / cancelable := true // Allow cancellation of forked task without killing SBT

resolvers += "Sonatype Releases" at "https://oss.sonatype.org/content/repositories/releases/"

def baseProject(id: String): Project = {
  Project(id, file(id))
    .settings(commonSettings: _*)
    .settings(name := s"alephium-$id")
}

val scalastyleCfgFile     = "project/scalastyle-config.xml"
val scalastyleTestCfgFile = "project/scalastyle-test-config.xml"

lazy val root: Project = Project("alephium-scala-blockflow", file("."))
  .settings(commonSettings: _*)
  .settings(
    name := "alephium",
    scalastyle := {},
    Test / scalastyle := {},
    publish / skip := true
  )
  .aggregate(
    macros,
    util,
    serde,
    io,
    crypto,
    api,
    rpc,
    app,
    benchmark,
    flow,
    json,
    conf,
    protocol,
    http,
    wallet,
    tools
  )

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
    Compile / compile / wartremoverErrors := Seq()
  )

lazy val util = project("util")
  .dependsOn(macros)
  .settings(
    libraryDependencies ++= Seq(
      akka,
      `akka-slf4j`,
      bcprov,
      `scala-reflect`(scalaVersion.value)
    )
  )

lazy val serde = project("serde")
  .settings(
    Compile / sourceGenerators += (Compile / sourceManaged).map(Boilerplate.genSrc).taskValue,
    Test / sourceGenerators += (Test / sourceManaged).map(Boilerplate.genTest).taskValue
  )
  .dependsOn(util % "test->test;compile->compile")

lazy val crypto = project("crypto")
  .dependsOn(util % "test->test;compile->compile", serde, json % "test->test")

lazy val io = project("io")
  .dependsOn(util % "test->test;compile->compile", serde, crypto)
  .settings(
    libraryDependencies += rocksdb
  )

lazy val rpc = project("rpc")
  .settings(
    libraryDependencies ++= Seq(
      `scala-logging`,
      `akka-test`
    ),
    publish / skip := true
  )
  .dependsOn(json, util % "test->test;compile->compile")

lazy val api = project("api")
  .dependsOn(
    json,
    protocol % "test->test;compile->compile",
    crypto,
    serde,
    util % "test->test;compile->compile"
  )
  .settings(
    libraryDependencies ++= Seq(
      `scala-logging`,
      `tapir-core`,
      `tapir-openapi`,
      `tapir-openapi-model`
    )
  )

lazy val app = mainProject("app")
  .dependsOn(
    json,
    api,
    rpc,
    http % "compile->compile;test->test",
    util % "it,test->test",
    flow,
    flow % "it,test->test",
    wallet
  )
  .enablePlugins(sbtdocker.DockerPlugin, BuildInfoPlugin)
  .settings(
    assembly / mainClass := Some("org.alephium.app.Boot"),
    assembly / assemblyJarName := s"alephium-${version.value}.jar",
    assembly / test := {},
    assembly / assemblyMergeStrategy := {
      case "logback.xml" => MergeStrategy.first
      case PathList("META-INF", "maven", "org.webjars", "swagger-ui", xs @ _*) =>
        MergeStrategy.first
      case PathList("META-INF", "io.netty.versions.properties", xs @ _*) =>
        MergeStrategy.first
      case "module-info.class" =>
        MergeStrategy.discard
      case other => (assembly / assemblyMergeStrategy).value(other)
    },
    libraryDependencies ++= Seq(
      janino,
      vertx,
      `tapir-core`,
      `tapir-vertx`,
      `tapir-openapi`,
      `tapir-swagger-ui`
    ),
    publish / skip := true,
    docker / dockerfile := {
      val artifact: File     = assembly.value
      val artifactTargetPath = "/alephium.jar"

      val userConf: File = file("docker/user.conf")

      val alephiumHome = "/alephium-home"

      new Dockerfile {
        from("openjdk:17-jdk")

        // Uncomment the next line and comment the previous one if you want to use GraalVM instead of OpenJDK
        // from("ghcr.io/graalvm/graalvm-ce:java11-21.0.0.2")

        runRaw(
          Seq(
            s"mkdir -p $alephiumHome && usermod -d $alephiumHome nobody && chown nobody $alephiumHome",
            s"mkdir -p $alephiumHome/.alephium && chown nobody $alephiumHome/.alephium",
            s"mkdir -p $alephiumHome/.alephium/mainnet && chown nobody $alephiumHome/.alephium/mainnet",
            s"mkdir -p $alephiumHome/.alephium-wallets && chown nobody $alephiumHome/.alephium-wallets"
          ).mkString(" && ")
        )
        workDir(alephiumHome)

        copy(userConf, file(s"$alephiumHome/.alephium/user.conf"))

        copy(artifact, artifactTargetPath)

        expose(12973) // http
        expose(11973) // ws
        expose(10973) // miner
        expose(9973)  // p2p

        volume(s"$alephiumHome/.alephium")
        volume(s"$alephiumHome/.alephium-wallets")

        user("nobody")

        entryPoint("java", "-jar", artifactTargetPath)
      }
    },
    docker / imageNames := {
      val baseImageName = "alephium/dev-alephium"
      val versionTag    = version.value.replace('+', '_')
      Seq(
        ImageName(baseImageName + ":" + versionTag),
        ImageName(baseImageName + ":" + versionTag + "-jdk17")
      )
    },
    buildInfoKeys := Seq[BuildInfoKey](
      name,
      scalaVersion,
      sbtVersion,
      BuildInfoKey("commitId"       -> git.gitHeadCommit.value.getOrElse("missing-git-commit")),
      BuildInfoKey("branch"         -> git.gitCurrentBranch.value),
      BuildInfoKey("releaseVersion" -> version.value)
    ),
    buildInfoPackage := "org.alephium.app",
    buildInfoUsePackageAsPath := true
  )

lazy val json = project("json")
  .dependsOn(util % "test->test")
  .settings(
    libraryDependencies ++= Seq(
      upickle
    )
  )

lazy val conf = project("conf")
  .dependsOn(protocol, util)
  .settings(
    libraryDependencies ++= Seq(
      ficus
    )
  )

lazy val http = project("http")
  .dependsOn(api, json, util % "test->test")
  .settings(
    libraryDependencies ++= Seq(
      `tapir-vertx`,
      `tapir-client`,
      `sttp-backend`
    )
  )

lazy val tools = mainProject("tools")
  .dependsOn(app)

lazy val benchmark = project("benchmark")
  .enablePlugins(JmhPlugin)
  .dependsOn(flow)
  .settings(
    publish / skip := true,
    scalacOptions += "-Xdisable-assertions",
    Compile / compile / wartremoverErrors := Seq.empty
  )

lazy val flow = project("flow")
  .dependsOn(conf, crypto, io, serde, util % "test->test")
  .settings(
    libraryDependencies ++= Seq(
      akka,
      `akka-slf4j`,
      logback,
      `scala-logging`,
      weupnp,
      `prometheus-simple-client`,
      `prometheus-simple-client-common`,
      `prometheus-simple-client-hotspot`
    ),
    publish / skip := true
  )
  .dependsOn(protocol % "test->test;compile->compile")

lazy val protocol = project("protocol")
  .enablePlugins(BuildInfoPlugin)
  .settings(
    buildInfoKeys := Seq[BuildInfoKey](version),
    buildInfoPackage := "org.alephium.protocol",
    buildInfoUsePackageAsPath := true
  )
  .dependsOn(
    crypto % "compile->compile;test->test",
    io     % "compile->compile;test->test",
    serde,
    util % "test->test"
  )
  .settings(
    libraryDependencies ++= Seq(
      `prometheus-simple-client`,
      fastparse
    )
  )

lazy val wallet = project("wallet")
  .dependsOn(
    conf,
    json,
    api,
    crypto,
    http     % "compile->compile;test->test",
    util     % "test->test",
    protocol % "compile->compile;test->test"
  )
  .settings(
    libraryDependencies ++= Seq(
      vertx,
      `scala-logging`,
      `tapir-core`,
      `tapir-openapi`,
      `tapir-swagger-ui`,
      `scala-logging`,
      logback
    ),
    publish / skip := true,
    assembly / mainClass := Some("org.alephium.wallet.Main"),
    assembly / assemblyJarName := s"alephium-wallet-${version.value}.jar",
    assembly / test := {},
    assembly / assemblyMergeStrategy := {
      case PathList("META-INF", "maven", "org.webjars", "swagger-ui", xs @ _*) =>
        MergeStrategy.first
      case PathList("META-INF", "io.netty.versions.properties", xs @ _*) =>
        MergeStrategy.first
      case "module-info.class" =>
        MergeStrategy.discard
      case other => (assembly / assemblyMergeStrategy).value(other)
    }
  )

val publishSettings = Seq(
  organization := "org.alephium",
  homepage := Some(url("https://github.com/alephium/alephium")),
  licenses := Seq("LGPL 3.0" -> new URL("https://www.gnu.org/licenses/lgpl-3.0.en.html")),
  developers := List(
    Developer(
      id = "alephium core dev",
      name = "alephium core dev",
      email = "dev@alephium.org",
      url = url("https://alephium.org/")
    )
  )
)

val commonSettings = publishSettings ++ Seq(
  scalaVersion := "2.13.6",
  Test / parallelExecution := false,
  scalacOptions ++= Seq(
//    "-Xdisable-assertions", // TODO: use this properly
    "-deprecation",
    "-encoding",
    "utf-8",
    "-explaintypes",
    "-feature",
    "-unchecked",
    "-Xsource:3",
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
    "-Xfatal-warnings",
    "-Ywarn-dead-code",
    "-Ywarn-extra-implicit",
    "-Ywarn-numeric-widen",
    "-Ywarn-unused:implicits",
    "-Ywarn-unused:imports",
    "-Ywarn-unused:locals",
    "-Ywarn-unused:params",
    "-Ywarn-unused:patvars",
    "-Ywarn-unused:privates",
    "-Ywarn-value-discard",
    "-Ymacro-annotations"
  ),
  Compile / console / scalacOptions --= Seq("-Ywarn-unused:imports", "-Xfatal-warnings"),
  Compile / compile / wartremoverErrors := Warts.allBut(wartsCompileExcludes: _*),
  Test / test / wartremoverErrors := Warts.allBut(wartsTestExcludes: _*),
  IntegrationTest / test / wartremoverErrors := Warts.allBut(wartsTestExcludes: _*),
  fork := true,
  javaOptions += "-Xss2m",
  Test / scalacOptions ++= Seq("-Xcheckinit", "-Wconf:cat=other-non-cooperative-equals:s"),
  Test / envVars += "ALEPHIUM_ENV" -> "test",
  Test / testOptions += Tests.Argument("-oD"),
  IntegrationTest / envVars += "ALEPHIUM_ENV" -> "it",
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
  Wart.Null,   // Partially covered by scalastyle, only use _ inside actors
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

addCommandAlias(
  "format",
  "scalafmtSbt;scalafmt;test:scalafmt;scalastyle;test:scalastyle;it:scalafmt;it:scalastyle;doc"
)

addCommandAlias(
  "unitTest",
  "scalafmtSbtCheck;scalafmtCheck;test:scalafmtCheck;scalastyle;test:scalastyle;coverage;test;coverageReport;doc"
)

addCommandAlias(
  "integrationTest",
  "it:scalafmtCheck;it:scalastyle;it:test"
)
