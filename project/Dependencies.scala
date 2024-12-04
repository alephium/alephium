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
  lazy val akka       = "2.6.20"
  lazy val tapir      = "1.10.7"
  lazy val sttp       = "3.9.6"
  lazy val apispec    = "0.10.0"
  lazy val prometheus = "0.16.0"
}

object Dependencies {
  lazy val akka         = "com.typesafe.akka" %% "akka-actor"   % Version.akka
  lazy val `akka-slf4j` = "com.typesafe.akka" %% "akka-slf4j"   % Version.akka
  lazy val `akka-test`  = "com.typesafe.akka" %% "akka-testkit" % Version.akka % Test

  lazy val vertx = "io.vertx" % "vertx-core" % "4.5.7"

  lazy val `upickle` = "com.lihaoyi" %% "upickle" % "3.3.0"

  lazy val ficus           = "com.iheart"                 %% "ficus"           % "1.5.2"
  lazy val bcprov          = "org.bouncycastle"            % "bcprov-jdk18on"  % "1.78.1"
  lazy val fastparse       = "com.lihaoyi"                %% "fastparse"       % "3.1.0"
  lazy val logback         = "ch.qos.logback"              % "logback-classic" % "1.5.6"
  lazy val rocksdb         = "org.rocksdb"                 % "rocksdbjni"      % "8.11.4"
  lazy val `scala-logging` = "com.typesafe.scala-logging" %% "scala-logging"   % "3.9.5"
  lazy val scalacheck      = "org.scalacheck"             %% "scalacheck"      % "1.18.0"  % Test
  lazy val scalatest       = "org.scalatest"              %% "scalatest"       % "3.2.18"  % Test
  lazy val scalatestplus   = "org.scalatestplus"          %% "scalacheck-1-14" % "3.2.2.0" % Test
  lazy val weupnp          = "org.bitlet"                  % "weupnp"          % "0.1.4"

  def `scala-reflect`(scalaVersion: String) = "org.scala-lang" % "scala-reflect" % scalaVersion

  lazy val `tapir-core`    = "com.softwaremill.sttp.tapir" %% "tapir-core"         % Version.tapir
  lazy val `tapir-server`  = "com.softwaremill.sttp.tapir" %% "tapir-server"       % Version.tapir
  lazy val `tapir-vertx`   = "com.softwaremill.sttp.tapir" %% "tapir-vertx-server" % Version.tapir
  lazy val `tapir-openapi` = "com.softwaremill.sttp.tapir" %% "tapir-openapi-docs" % Version.tapir
  lazy val `tapir-openapi-model` =
    "com.softwaremill.sttp.apispec" %% "openapi-model" % Version.apispec
  lazy val `tapir-swagger-ui` =
    "com.softwaremill.sttp.tapir" %% "tapir-swagger-ui" % Version.tapir
  lazy val `tapir-client` = "com.softwaremill.sttp.tapir" %% "tapir-sttp-client" % Version.tapir
  lazy val `tapir-files`  = "com.softwaremill.sttp.tapir" %% "tapir-files"       % Version.tapir
  lazy val `sttp-backend` =
    "com.softwaremill.sttp.client3" %% "async-http-client-backend-future" % Version.sttp

  lazy val `prometheus-simple-client` = "io.prometheus" % "simpleclient" % Version.prometheus
  lazy val `prometheus-simple-client-common` =
    "io.prometheus" % "simpleclient_common" % Version.prometheus
  lazy val `prometheus-simple-client-hotspot` =
    "io.prometheus" % "simpleclient_hotspot" % Version.prometheus
  lazy val scopt = "com.github.scopt" %% "scopt" % "4.1.0"

  lazy val `tapir-prometheus-metrics` =
    "com.softwaremill.sttp.tapir" %% "tapir-prometheus-metrics" % Version.tapir
}
