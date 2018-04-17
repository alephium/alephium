import sbt._

object Version {
  lazy val akka = "2.5.12"
}

object Dependencies {
  lazy val scalaTest       = "org.scalatest"              %% "scalatest"      % "3.0.5"
  lazy val akka            = "com.typesafe.akka"          %% "akka-actor"     % Version.akka
  lazy val `akka-slf4j`    = "com.typesafe.akka"          %% "akka-slf4j"     % Version.akka
  lazy val akkaTest        = "com.typesafe.akka"          %% "akka-testkit"   % Version.akka % Test
  lazy val bcprov          = "org.bouncycastle"           % "bcprov-jdk15on"  % "1.59"
  lazy val `scala-logging` = "com.typesafe.scala-logging" %% "scala-logging"  % "3.9.0"
  lazy val logback         = "ch.qos.logback"             % "logback-classic" % "1.2.3"
  lazy val scalaCheck      = "org.scalacheck"             %% "scalacheck"     % "1.13.5" % Test
  lazy val curve25519      = "org.whispersystems"         % "curve25519-java" % "0.4.1"
}
