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

package org.alephium

import java.io.File
import java.net.{InetAddress, InetSocketAddress}
import java.nio.file.Path

import scala.collection.immutable.ArraySeq
import scala.concurrent.duration.MILLISECONDS
import scala.jdk.CollectionConverters._
import scala.util.{Failure, Success, Try}

import com.typesafe.config.{Config, ConfigException}
import net.ceedubs.ficus.Ficus._
import net.ceedubs.ficus.readers.CollectionReaders.traversableReader
import net.ceedubs.ficus.readers.ValueReader
import net.ceedubs.ficus.readers.namemappers.implicits.hyphenCase

import org.alephium.util.{Duration, U256}

package object conf {

  def valueReader[A](f: Cfg => A): ValueReader[A] = ValueReader.relative { config =>
    f(Cfg(config))
  }

  def as[A: ValueReader](path: String)(implicit config: Cfg): A = {
    config.as[A](hyphenCase.map(path))
  }

  implicit val pathValueReader: ValueReader[Path] = ValueReader[String].map { path =>
    (new File(path)).toPath()
  }

  implicit val u256ValueReader: ValueReader[U256] = bigIntReader.map { bigInt =>
    U256
      .from(bigInt.bigInteger)
      .getOrElse(
        throw new ConfigException.BadValue("U256", s"$bigInt")
      )
  }

  implicit val durationValueReader: ValueReader[Duration] = new ValueReader[Duration] {
    def read(config: Config, path: String): Duration = {
      val millis = config.getDuration(path, MILLISECONDS)
      Duration
        .ofMillis(millis)
        .getOrElse(
          throw new ConfigException.ValidationFailed(
            Iterable(
              new ConfigException.ValidationProblem(
                path,
                config.origin(),
                "negative duration"
              )
            ).asJava
          )
        )
    }
  }

  implicit val inetAddressConfig: ValueReader[InetAddress] =
    ValueReader[String].map { input =>
      parseHost(input).getOrElse(
        throw new ConfigException.BadValue("InetAddress", "oops")
      )
    }

  implicit val inetSocketAddressesReader: ValueReader[ArraySeq[InetSocketAddress]] =
    new ValueReader[ArraySeq[InetSocketAddress]] {
      def read(config: Config, path: String): ArraySeq[InetSocketAddress] = {
        Try(
          traversableReader[ArraySeq, InetSocketAddress].read(config, path)
        ) match {
          case Success(res) => res
          case Failure(_)   => inetSocketAddressesStringReader.read(config, path)
        }
      }
    }

  private val inetSocketAddressesStringReader: ValueReader[ArraySeq[InetSocketAddress]] =
    ValueReader[String].map(_.split(",")).map {
      case Array(empty) if empty == "" => ArraySeq.empty[InetSocketAddress]
      case inputs =>
        val result = inputs.flatMap(parseHostAndPort)
        if (result.size == inputs.size) {
          ArraySeq.from(result)
        } else {
          throw new ConfigException.BadValue("ArraySeq[InetSocketAddress]", "oops")
        }
    }

  private def parseHost(unparsedHost: String): Option[InetAddress] = {
    val hostAndPort = """([a-zA-Z0-9\.\-]+)\s*""".r
    unparsedHost match {
      case hostAndPort(host) =>
        Some(InetAddress.getByName(host))
      case _ =>
        None
    }
  }

  private def parseHostAndPort(unparsedHostAndPort: String): Option[InetSocketAddress] = {
    val hostAndPort = """([a-zA-Z0-9\.\-]+)\s*:\s*(\d+)""".r
    unparsedHostAndPort match {
      case hostAndPort(host, port) =>
        Some(new InetSocketAddress(host, port.toInt))
      case _ =>
        None
    }
  }
}

final private class Cfg private (config: Config) {
  def as[A: ValueReader](path: String): A = {
    config.as[A](hyphenCase.map(path))
  }
}
private object Cfg {
  def apply(config: Config): Cfg = new Cfg(config)
}
