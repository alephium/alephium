package org.alephium.rpc.model

import io.circe._
import io.circe.generic.semiauto._

object BlockFlowRPC {

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
