// Copyright 2018 The Alephium Authors
// This file is part of the alephium project.
//
// The library is free software: you can redistribute it and/or modify
// it under the terms of the GNU Lesser General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// The library is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
// GNU Lesser General Public License for more details.
//
// You should have received a copy of the GNU Lesser General Public License
// along with the library. If not, see <http://www.gnu.org/licenses/>.

import sbt._

object Version {
  lazy val akka        = "2.6.13"
  lazy val `akka-http` = "10.2.4"
  lazy val metrics     = "4.0.6"
  lazy val tapir       = "0.17.19"
  lazy val sttp        = "3.2.3"
}

object Dependencies {
  lazy val akka                = "com.typesafe.akka" %% "akka-actor"        % Version.akka
  lazy val `akka-http`         = "com.typesafe.akka" %% "akka-http"         % Version.`akka-http`
  lazy val `akka-http-upickle` = "de.heikoseeberger" %% "akka-http-upickle" % "1.36.0"
  lazy val `akka-slf4j`        = "com.typesafe.akka" %% "akka-slf4j"        % Version.akka
  lazy val `akka-stream`       = "com.typesafe.akka" %% "akka-stream"       % Version.akka
  lazy val `akka-test`         = "com.typesafe.akka" %% "akka-testkit"      % Version.akka % Test
  lazy val `akka-http-test` =
    "com.typesafe.akka" %% "akka-http-testkit" % Version.`akka-http` % Test
  lazy val `akka-http-cors`   = "ch.megard"         %% "akka-http-cors"      % "1.1.1"
  lazy val `akka-stream-test` = "com.typesafe.akka" %% "akka-stream-testkit" % Version.akka % Test

  lazy val `upickle` = "com.lihaoyi" %% "upickle" % "1.3.8"

  lazy val ficus           = "com.iheart"                 %% "ficus"           % "1.5.0"
  lazy val bcprov          = "org.bouncycastle"            % "bcprov-jdk15on"  % "1.68"
  lazy val fastparse       = "com.lihaoyi"                %% "fastparse"       % "2.3.1"
  lazy val logback         = "ch.qos.logback"              % "logback-classic" % "1.2.3"
  lazy val metrics         = "io.dropwizard.metrics"       % "metrics-core"    % Version.metrics
  lazy val `metrics-jmx`   = "io.dropwizard.metrics"       % "metrics-jmx"     % Version.metrics
  lazy val rocksdb         = "org.rocksdb"                 % "rocksdbjni"      % "5.18.4"
  lazy val `scala-logging` = "com.typesafe.scala-logging" %% "scala-logging"   % "3.9.2"
  lazy val scalacheck      = "org.scalacheck"             %% "scalacheck"      % "1.15.3"  % Test
  lazy val scalatest       = "org.scalatest"              %% "scalatest"       % "3.2.6"   % Test
  lazy val scalatestplus   = "org.scalatestplus"          %% "scalacheck-1-14" % "3.2.2.0" % Test
  lazy val weupnp          = "org.bitlet"                  % "weupnp"          % "0.1.4"

  def `scala-reflect`(scalaVersion: String) = "org.scala-lang" % "scala-reflect" % scalaVersion

  lazy val `tapir-core`    = "com.softwaremill.sttp.tapir" %% "tapir-core"             % Version.tapir
  lazy val `tapir-akka`    = "com.softwaremill.sttp.tapir" %% "tapir-akka-http-server" % Version.tapir
  lazy val `tapir-openapi` = "com.softwaremill.sttp.tapir" %% "tapir-openapi-docs"     % Version.tapir
  lazy val `tapir-openapi-model` =
    "com.softwaremill.sttp.tapir" %% "tapir-openapi-model" % Version.tapir
  lazy val `tapir-swagger-ui` =
    "com.softwaremill.sttp.tapir" %% "tapir-swagger-ui-akka-http" % Version.tapir
  lazy val `tapir-client` = "com.softwaremill.sttp.tapir" %% "tapir-sttp-client" % Version.tapir
  lazy val `sttp-akka-http-backend` =
    "com.softwaremill.sttp.client3" %% "akka-http-backend" % Version.sttp
}
