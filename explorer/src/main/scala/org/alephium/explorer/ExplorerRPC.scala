package org.alephium.explorer

import io.circe._
import io.circe.generic.semiauto._

object ExplorerRPC {
  case class BlocksRequest(
      size: Int
  )

  object BlocksRequest {
    implicit val decoder: Decoder[BlocksRequest] = deriveDecoder[BlocksRequest]
    implicit val encoder: Encoder[BlocksRequest] = deriveEncoder[BlocksRequest]
  }
}
