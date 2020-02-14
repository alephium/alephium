package org.alephium.appserver

import io.circe._
import io.circe.generic.semiauto._

import org.alephium.protocol.model.{CliqueId, CliqueInfo}
import org.alephium.rpc.CirceUtils._
import org.alephium.util.{AVector, Hex, TimeStamp}

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

  case class PeersResult(cliques: AVector[CliqueInfo])
  object PeersResult {
    def createId(s: String): Either[String, CliqueId] = {
      Hex.from(s).flatMap(CliqueId.from) match {
        case Some(id) => Right(id)
        case None     => Left("invalid clique id")
      }
    }

    implicit val idEncoder: Encoder[CliqueId]       = Encoder.encodeString.contramap(_.toHexString)
    implicit val idDecoder: Decoder[CliqueId]       = Decoder.decodeString.emap(createId)
    implicit val cliqueInfoCodec: Codec[CliqueInfo] = deriveCodec[CliqueInfo]
    implicit val codec: Codec[PeersResult]          = deriveCodec[PeersResult]
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

  case class Transfer(fromAddress: String,
                      fromType: String,
                      toAddress: String,
                      toType: String,
                      value: BigInt,
                      fromPrivateKey: String)
  object Transfer {
    implicit val codec: Codec[Transfer] = deriveCodec[Transfer]
  }

  case class TransferResult(txId: String)
  object TransferResult {
    implicit val codec: Codec[TransferResult] = deriveCodec[TransferResult]
  }
}
