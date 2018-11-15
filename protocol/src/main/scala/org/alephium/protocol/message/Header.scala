package org.alephium.protocol.message

import org.alephium.protocol.Protocol
import org.alephium.serde.{Serde, WrongFormatException}

import scala.util.{Failure, Success}

case class Header(version: Int)

object Header {
  implicit val serde: Serde[Header] =
    Serde
      .tuple1[Int]
      .xfmap(
        version =>
          if (version == Protocol.version) Success(Header(version))
          else
            Failure(
              WrongFormatException(s"Invalid version, got $version, expect ${Protocol.version}")),
        header => header.version
      )
}
