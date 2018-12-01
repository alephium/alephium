import Dependencies._

resolvers += "Sonatype Releases" at "https://oss.sonatype.org/content/repositories/releases/"

lazy val root = (project in file(".")).
  settings(commonSettings: _*).
  settings(
    libraryDependencies ++= dependencies
  )

val commonSettings = Seq(
  organization := "org.alephium",
  name := "alephium",
  version := "0.1.0-SNAPSHOT",
  scalaVersion := "2.12.3",
)

val dependencies = Seq(
  akka,
  `akka-slf4j`,
  scrypto,
  `scala-logging`,
  logback
)
