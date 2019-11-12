package org.alephium.appserver

import io.circe._
import io.circe.generic.semiauto._

import org.alephium.util.TimeStamp

object RPCModel {
  case class FetchRequest(from: Option[TimeStamp])

  object TimeStampCodec {
    implicit val decoderTS: Decoder[TimeStamp] =
      Decoder.decodeLong.ensure(_ >= 0, s"Expect positive timestamp").map(TimeStamp.fromMillis)
    implicit val encoderTS: Encoder[TimeStamp] = Encoder.encodeLong.contramap(_.millis)
  }

  object FetchRequest {
    import TimeStampCodec._
    implicit val decoder: Decoder[FetchRequest] = deriveDecoder[FetchRequest]
  }

  case class FetchResponse(blocks: List[FetchEntry])
  object FetchResponse {
    implicit val decoder: Decoder[FetchResponse] = deriveDecoder[FetchResponse]
    implicit val encoder: Encoder[FetchResponse] = deriveEncoder[FetchResponse]
  }

  case class FetchEntry(
      hash: String,
      timestamp: TimeStamp,
      chainFrom: Int,
      chainTo: Int,
      height: Int,
      deps: List[String]
  )

  object FetchEntry {
    import TimeStampCodec._
    implicit val decoder: Decoder[FetchEntry] = deriveDecoder[FetchEntry]
    implicit val encoder: Encoder[FetchEntry] = deriveEncoder[FetchEntry]
  }
}
