import sbt._

object Version {
  lazy val akka        = "2.6.6"
  lazy val `akka-http` = "10.1.12"
  lazy val circe       = "0.13.0"
  lazy val metrics     = "4.0.6"
  lazy val tapir       = "0.15.4"
}

object Dependencies {
  lazy val akka               = "com.typesafe.akka" %% "akka-actor"          % Version.akka
  lazy val `akka-http`        = "com.typesafe.akka" %% "akka-http"           % Version.`akka-http`
  lazy val `akka-http-circe`  = "de.heikoseeberger" %% "akka-http-circe"     % "1.32.0"
  lazy val `akka-slf4j`       = "com.typesafe.akka" %% "akka-slf4j"          % Version.akka
  lazy val `akka-stream`      = "com.typesafe.akka" %% "akka-stream"         % Version.akka
  lazy val `akka-test`        = "com.typesafe.akka" %% "akka-testkit"        % Version.akka % Test
  lazy val `akka-http-test`   = "com.typesafe.akka" %% "akka-http-testkit"   % Version.`akka-http` % Test
  lazy val `akka-http-cors`   = "ch.megard"         %% "akka-http-cors"      % "0.4.3"
  lazy val `akka-stream-test` = "com.typesafe.akka" %% "akka-stream-testkit" % Version.akka % Test

  lazy val `circe-parser`  = "io.circe"                   %% "circe-parser"    % Version.circe
  lazy val `circe-generic` = "io.circe"                   %% "circe-generic"   % Version.circe
  lazy val pureconfig      = "com.github.pureconfig"      %% "pureconfig"      % "0.13.0"
  lazy val bcprov          = "org.bouncycastle"           % "bcprov-jdk15on"   % "1.64"
  lazy val fastparse       = "com.lihaoyi"                %% "fastparse"       % "2.2.2"
  lazy val logback         = "ch.qos.logback"             % "logback-classic"  % "1.2.3"
  lazy val metrics         = "io.dropwizard.metrics"      % "metrics-core"     % Version.metrics
  lazy val `metrics-jmx`   = "io.dropwizard.metrics"      % "metrics-jmx"      % Version.metrics
  lazy val rocksdb         = "org.rocksdb"                % "rocksdbjni"       % "5.18.3"
  lazy val `scala-logging` = "com.typesafe.scala-logging" %% "scala-logging"   % "3.9.2"
  lazy val scalacheck      = "org.scalacheck"             %% "scalacheck"      % "1.14.3" % Test
  lazy val scalatest       = "org.scalatest"              %% "scalatest"       % "3.1.1" % Test
  lazy val scalatestplus   = "org.scalatestplus"          %% "scalacheck-1-14" % "3.1.1.1" % Test

  def `scala-reflect`(scalaVersion: String) = "org.scala-lang" % "scala-reflect" % scalaVersion

  lazy val `tapir-core`          = "com.softwaremill.sttp.tapir" %% "tapir-core"                 % Version.tapir
  lazy val `tapir-circe`         = "com.softwaremill.sttp.tapir" %% "tapir-json-circe"           % Version.tapir
  lazy val `tapir-akka`          = "com.softwaremill.sttp.tapir" %% "tapir-akka-http-server"     % Version.tapir
  lazy val `tapir-openapi`       = "com.softwaremill.sttp.tapir" %% "tapir-openapi-docs"         % Version.tapir
  lazy val `tapir-openapi-circe` = "com.softwaremill.sttp.tapir" %% "tapir-openapi-circe-yaml"   % Version.tapir
  lazy val `tapir-swagger-ui`    = "com.softwaremill.sttp.tapir" %% "tapir-swagger-ui-akka-http" % Version.tapir
}
