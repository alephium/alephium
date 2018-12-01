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
  parallelExecution in Test := false,
  scalacOptions := Seq(
    "-unchecked",
    "-deprecation",
    "-feature",
    "-Xfatal-warnings",
    "-Xlint:unsound-match",
    "-Ywarn-inaccessible",
    "-Ywarn-unused-import",
    "-encoding", "utf-8",
  ),
  scalacOptions in Test += "-Xcheckinit"
)

val dependencies = Seq(
  akka,
  `akka-slf4j`,
  shapeless,
  scrypto,
  `scala-logging`,
  logback,
  scalaTest,
  scalaCheck
)
