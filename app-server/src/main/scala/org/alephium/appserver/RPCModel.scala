package org.alephium.appserver

import io.circe._
import io.circe.generic.semiauto._

import org.alephium.util.TimeStamp

object RPCModel {
  object TimeStampCodec {
    implicit val decoderTS: Decoder[TimeStamp] =
      Decoder.decodeLong.ensure(_ >= 0, s"Expect positive timestamp").map(TimeStamp.fromMillis)
    implicit val encoderTS: Encoder[TimeStamp] = Encoder.encodeLong.contramap(_.millis)
  }

  case class FetchRequest(from: Option[TimeStamp])
  object FetchRequest {
    import TimeStampCodec._
    implicit val codec: Codec[FetchRequest] = deriveCodec[FetchRequest]
  }

  case class FetchResponse(blocks: Seq[FetchEntry])
  object FetchResponse {
    implicit val codec: Codec[FetchResponse] = deriveCodec[FetchResponse]
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
    implicit val codec: Codec[FetchEntry] = deriveCodec[FetchEntry]
  }

  case class GetBalance(address: String, `type`: String)
  object GetBalance {
    implicit val codec: Codec[GetBalance] = deriveCodec[GetBalance]

    // TODO: refactor this once script system gets mature
    val pkh = "pkh"
  }

  case class Balance(balance: BigInt, utxoNum: Int)
  object Balance {
    implicit val codec: Codec[Balance] = deriveCodec[Balance]
  }
}
