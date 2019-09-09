import sbt._

object Version {
  lazy val akka    = "2.5.24"
  lazy val circe   = "0.9.3"
  lazy val metrics = "4.0.3"
}

object Dependencies {
  lazy val akka            = "com.typesafe.akka"          %% "akka-actor"     % Version.akka
  lazy val `akka-http`     = "com.typesafe.akka"          %% "akka-http"      % "10.1.3"
  lazy val `akka-slf4j`    = "com.typesafe.akka"          %% "akka-slf4j"     % Version.akka
  lazy val `akka-stream`   = "com.typesafe.akka"          %% "akka-stream"    % Version.akka
  lazy val akkatest        = "com.typesafe.akka"          %% "akka-testkit"   % Version.akka % Test
  lazy val bcprov          = "org.bouncycastle"           % "bcprov-jdk15on"  % "1.59"
  lazy val `circe-parser`  = "io.circe"                   %% "circe-parser"   % Version.circe
  lazy val `circe-generic` = "io.circe"                   %% "circe-generic"  % Version.circe
  lazy val curve25519      = "org.whispersystems"         % "curve25519-java" % "0.4.1"
  lazy val logback         = "ch.qos.logback"             % "logback-classic" % "1.2.3"
  lazy val metrics         = "io.dropwizard.metrics"      % "metrics-core"    % Version.metrics
  lazy val `metrics-jmx`   = "io.dropwizard.metrics"      % "metrics-jmx"     % Version.metrics
  lazy val `scala-logging` = "com.typesafe.scala-logging" %% "scala-logging"  % "3.9.0"
  lazy val scalacheck      = "org.scalacheck"             %% "scalacheck"     % "1.13.5" % Test
  lazy val scalatest       = "org.scalatest"              %% "scalatest"      % "3.0.5" % Test
  lazy val rocksdb         = "org.rocksdb"                % "rocksdbjni"      % "5.15.10"

  def `scala-reflect`(scalaVersion: String) = "org.scala-lang" % "scala-reflect" % scalaVersion
}
