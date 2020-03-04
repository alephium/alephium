package org.alephium.protocol.message

import org.alephium.protocol.Protocol
import org.alephium.serde.{Serde, SerdeError}

final case class Header(version: Int)

object Header {
  implicit val serde: Serde[Header] =
    Serde
      .tuple1[Int]
      .xfmap(
        version =>
          if (version == Protocol.version) Right(Header(version))
          else
            Left(
              SerdeError.wrongFormat(s"Invalid version, got $version, expect ${Protocol.version}")),
        header => header.version
      )
}
