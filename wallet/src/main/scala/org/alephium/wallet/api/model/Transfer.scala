package org.alephium.wallet.api.model

import io.circe.Codec
import io.circe.generic.semiauto.deriveCodec

final case class Transfer(address: String, amount: Long)

object Transfer {
  implicit val codec: Codec[Transfer] = deriveCodec[Transfer]

  final case class Result(txId: String)
  object Result {
    implicit val codec: Codec[Result] = deriveCodec[Result]
  }
}
