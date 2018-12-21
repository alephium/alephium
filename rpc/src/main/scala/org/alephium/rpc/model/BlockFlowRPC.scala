package org.alephium.rpc.model

import io.circe._
import io.circe.generic.semiauto._

import org.alephium.flow.storage.MultiChain
import org.alephium.protocol.config.ConsensusConfig
import org.alephium.protocol.model.BlockHeader

object BlockFlowRPC {

  def blockHeaderEncoder(chain: MultiChain)(
      implicit config: ConsensusConfig): Encoder[BlockHeader] = new Encoder[BlockHeader] {
    final def apply(header: BlockHeader): Json = {
      import io.circe.syntax._

      val index     = header.chainIndex
      val from      = index.from.value
      val to        = index.to.value
      val timestamp = header.timestamp
      val height    = chain.getHeight(header)
      val hash      = header.shortHex
      val deps      = header.blockDeps.toIterable.map(_.shortHex).toList

      Json.obj(
        ("timestamp", Json.fromLong(timestamp)),
        ("chainFrom", Json.fromInt(from)),
        ("chainTo", Json.fromInt(to)),
        ("height", Json.fromInt(height)),
        ("hash", Json.fromString(hash)),
        ("deps", deps.asJson)
      )
    }
  }

  case class FetchRequest(from: Option[Long])

  object FetchRequest {
    implicit val decoder: Decoder[FetchRequest] = deriveDecoder[FetchRequest]
    implicit val encoder: Encoder[FetchRequest] = deriveEncoder[FetchRequest]
  }
}
