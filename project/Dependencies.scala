import sbt._

object Version {
  lazy val akka = "2.5.11"
}

object Dependencies {
  lazy val scalaTest       = "org.scalatest"              %% "scalatest"      % "3.0.3"
  lazy val cats            = "org.typelevel"              %% "cats"           % "0.9.0"
  lazy val akka            = "com.typesafe.akka"          %% "akka-actor"     % Version.akka
  lazy val `akka-slf4j`    = "com.typesafe.akka"          %% "akka-slf4j"     % Version.akka
  lazy val scrypto         = "org.scorexfoundation"       %% "scrypto"        % "2.0.0"
  lazy val `scala-logging` = "com.typesafe.scala-logging" %% "scala-logging"  % "3.8.0"
  lazy val logback         = "ch.qos.logback"             % "logback-classic" % "1.2.3"
  lazy val shapeless       = "com.chuusai"                %% "shapeless"      % "2.3.3"
  lazy val akkTest         = "com.typesafe.akka"          %% "akka-testkit"   % Version.akka % Test
  lazy val scalaCheck      = "org.scalacheck"             %% "scalacheck"     % "1.13.5" % Test
}
