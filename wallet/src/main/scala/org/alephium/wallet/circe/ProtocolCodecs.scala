package org.alephium.wallet.circe

import io.circe._

import org.alephium.protocol.Hash
import org.alephium.protocol.model.{Address, NetworkType}

trait ProtocolCodecs extends UtilCodecs {

  def networkType: NetworkType

  lazy val addressEncoder: Encoder[Address] =
    Encoder.encodeString.contramap[Address](_.toBase58)
  lazy val addressDecoder: Decoder[Address] =
    Decoder.decodeString.emap { input =>
      Address
        .fromBase58(input, networkType)
        .toRight(s"Unable to decode address from $input")
    }
  implicit lazy val addressCodec: Codec[Address] = Codec.from(addressDecoder, addressEncoder)

  implicit val hashEncoder: Encoder[Hash] = hash => Json.fromString(hash.toHexString)
  implicit val hashDecoder: Decoder[Hash] =
    byteStringDecoder.emap(Hash.from(_).toRight("cannot decode hash"))
  implicit val hashCodec: Codec[Hash] = Codec.from(hashDecoder, hashEncoder)
}
