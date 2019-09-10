import sbt._

object Version {
  lazy val akka    = "2.5.25"
  lazy val metrics = "4.0.6"
}

object Dependencies {
  lazy val `alephium-crypto` = "org.alephium" %% "crypto" % "latest.integration"
  lazy val `alephium-rpc`    = "org.alephium" %% "rpc"    % "latest.integration"
  lazy val `alephium-serde`  = "org.alephium" %% "serde"  % "latest.integration"
  lazy val `alephium-util`   = "org.alephium" %% "util"   % "latest.integration"

  lazy val akka            = "com.typesafe.akka"          %% "akka-actor"     % Version.akka
  lazy val akkatest        = "com.typesafe.akka"          %% "akka-testkit"   % Version.akka % Test
  lazy val logback         = "ch.qos.logback"             % "logback-classic" % "1.2.3"
  lazy val metrics         = "io.dropwizard.metrics"      % "metrics-core"    % Version.metrics
  lazy val `metrics-jmx`   = "io.dropwizard.metrics"      % "metrics-jmx"     % Version.metrics
  lazy val rocksdb         = "org.rocksdb"                % "rocksdbjni"      % "5.18.3"
  lazy val `scala-logging` = "com.typesafe.scala-logging" %% "scala-logging"  % "3.9.2"
  lazy val scalacheck      = "org.scalacheck"             %% "scalacheck"     % "1.14.0" % Test
  lazy val scalatest       = "org.scalatest"              %% "scalatest"      % "3.0.8" % Test
}
