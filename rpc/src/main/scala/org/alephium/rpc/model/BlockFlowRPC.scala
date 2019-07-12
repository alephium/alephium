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

      val index = header.chainIndex

      FetchEntry(
        hash      = header.shortHex,
        timestamp = header.timestamp,
        chainFrom = index.from.value,
        chainTo   = index.to.value,
        height    = chain.getHeight(header),
        deps      = header.blockDeps.toIterable.map(_.shortHex).toList
      ).asJson
    }
  }

  case class FetchRequest(from: Option[Long])

  object FetchRequest {
    implicit val decoder: Decoder[FetchRequest] = deriveDecoder[FetchRequest]
    implicit val encoder: Encoder[FetchRequest] = deriveEncoder[FetchRequest]
  }

  case class FetchResponse(blocks: List[FetchEntry])
  object FetchResponse {
    implicit val decoder: Decoder[FetchResponse] = deriveDecoder[FetchResponse]
    implicit val encoder: Encoder[FetchResponse] = deriveEncoder[FetchResponse]
  }

  case class FetchEntry(
      hash: String,
      timestamp: Long,
      chainFrom: Int,
      chainTo: Int,
      height: Int,
      deps: List[String]
  )

  object FetchEntry {
    implicit val decoder: Decoder[FetchEntry] = deriveDecoder[FetchEntry]
    implicit val encoder: Encoder[FetchEntry] = deriveEncoder[FetchEntry]
  }
}
