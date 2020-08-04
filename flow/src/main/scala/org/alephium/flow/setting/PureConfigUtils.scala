package org.alephium.flow.setting

import java.net.{InetAddress, InetSocketAddress}

import scala.collection.immutable.ArraySeq
import scala.concurrent.duration.FiniteDuration

import pureconfig.ConfigReader
import pureconfig.error.CannotConvert

import org.alephium.crypto.Sha256
import org.alephium.util.{Duration, Hex}

object PureConfigUtils {
  implicit val durationConfig: ConfigReader[Duration] =
    ConfigReader[FiniteDuration].emap { dt =>
      val millis = dt.toMillis
      if (millis >= 0) Right(Duration.ofMillisUnsafe(millis))
      else Left(CannotConvert(dt.toString, "alephium Duration", "negative duration"))
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
  implicit val inetSocketAddressConfig: ConfigReader[InetSocketAddress] =
    ConfigReader[String].emap { addressInput =>
      parseHostAndPort(addressInput).toRight(
        CannotConvert(addressInput, "InetSocketAddress", "oops"))
    }

  implicit val inetAddressConfig: ConfigReader[InetAddress] =
    ConfigReader[String].emap { input =>
      parseHost(input).toRight(
        CannotConvert(input, "InetAddress", "oops")
      )
    }

  implicit val sha256Config: ConfigReader[Sha256] =
    ConfigReader[String].emap { hashInput =>
      Hex
        .from(hashInput)
        .flatMap(Sha256.from)
        .toRight(CannotConvert(hashInput, "Sha256", "oops"))
    }

  private val bootstrapStringReader = ConfigReader[String].map(_.split(",")).emap {
    case Array(empty) if empty == "" => Right(ArraySeq.empty)
    case inputs =>
      val result = inputs.flatMap(parseHostAndPort)
      Either.cond(result.size == inputs.size,
                  ArraySeq.from(result),
                  CannotConvert(inputs.mkString(", "), "ArraySeq[InetAddress]", "oops"))
  }

  //We can't put explicitly the type, otherwise the automatic derivation of `pureconfig` fail
  @SuppressWarnings(Array("org.wartremover.warts.PublicInference"))
  implicit val bootstrapReader =
    ConfigReader[ArraySeq[InetSocketAddress]].orElse(bootstrapStringReader)
}
