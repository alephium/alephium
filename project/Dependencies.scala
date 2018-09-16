import sbt._

object Version {
  lazy val akka  = "2.5.12"
  lazy val circe = "0.9.3"
}

object Dependencies {
  lazy val scalatest       = "org.scalatest"              %% "scalatest"      % "3.0.5" % Test
  lazy val akka            = "com.typesafe.akka"          %% "akka-actor"     % Version.akka
  lazy val `akka-slf4j`    = "com.typesafe.akka"          %% "akka-slf4j"     % Version.akka
  lazy val akkatest        = "com.typesafe.akka"          %% "akka-testkit"   % Version.akka % Test
  lazy val `akka-stream`   = "com.typesafe.akka"          %% "akka-stream"    % Version.akka
  lazy val `akka-http`     = "com.typesafe.akka"          %% "akka-http"      % "10.1.3"
  lazy val bcprov          = "org.bouncycastle"           % "bcprov-jdk15on"  % "1.59"
  lazy val `scala-logging` = "com.typesafe.scala-logging" %% "scala-logging"  % "3.9.0"
  lazy val logback         = "ch.qos.logback"             % "logback-classic" % "1.2.3"
  lazy val scalacheck      = "org.scalacheck"             %% "scalacheck"     % "1.13.5" % Test
  lazy val curve25519      = "org.whispersystems"         % "curve25519-java" % "0.4.1"
  lazy val `circe-parser`  = "io.circe"                   %% "circe-parser"   % Version.circe
}
