import sbt._

object Version {
  lazy val akka    = "2.6.4"
  lazy val metrics = "4.0.6"
}

object Dependencies {
  lazy val `alephium-crypto` = "org.alephium" %% "crypto" % "latest.integration"
  lazy val `alephium-rpc`    = "org.alephium" %% "rpc"    % "latest.integration"
  lazy val `alephium-serde`  = "org.alephium" %% "serde"  % "latest.integration"
  lazy val `alephium-util`   = "org.alephium" %% "util"   % "latest.integration"

  lazy val akka           = "com.typesafe.akka" %% "akka-actor"          % Version.akka
  lazy val akkatest       = "com.typesafe.akka" %% "akka-testkit"        % Version.akka % Test
  lazy val akkahttptest   = "com.typesafe.akka" %% "akka-http-testkit"   % "10.1.11" % Test
  lazy val akkahttpcors   = "ch.megard"         %% "akka-http-cors"      % "0.4.2"
  lazy val akkastreamtest = "com.typesafe.akka" %% "akka-stream-testkit" % Version.akka % Test

  lazy val logback         = "ch.qos.logback"             % "logback-classic"  % "1.2.3"
  lazy val metrics         = "io.dropwizard.metrics"      % "metrics-core"     % Version.metrics
  lazy val `metrics-jmx`   = "io.dropwizard.metrics"      % "metrics-jmx"      % Version.metrics
  lazy val rocksdb         = "org.rocksdb"                % "rocksdbjni"       % "5.18.3"
  lazy val `scala-logging` = "com.typesafe.scala-logging" %% "scala-logging"   % "3.9.2"
  lazy val scalacheck      = "org.scalacheck"             %% "scalacheck"      % "1.14.3" % Test
  lazy val scalatest       = "org.scalatest"              %% "scalatest"       % "3.1.1" % Test
  lazy val scalatestplus   = "org.scalatestplus"          %% "scalacheck-1-14" % "3.1.1.1" % Test
}
