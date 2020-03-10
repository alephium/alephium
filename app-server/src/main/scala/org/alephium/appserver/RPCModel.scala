package org.alephium.appserver

import io.circe._
import io.circe.generic.semiauto._

import org.alephium.flow.core.FlowHandler.BlockNotify
import org.alephium.protocol.config.GroupConfig
import org.alephium.protocol.model.{BlockHeader, CliqueId, CliqueInfo}
import org.alephium.rpc.CirceUtils._
import org.alephium.util.{AVector, Hex, TimeStamp}

object RPCModel {
  object TimeStampCodec {
    implicit val decoderTS: Decoder[TimeStamp] =
      Decoder.decodeLong.ensure(_ >= 0, s"expect positive timestamp").map(TimeStamp.unsafe)
    implicit val encoderTS: Encoder[TimeStamp] = Encoder.encodeLong.contramap(_.millis)
  }

  final case class FetchRequest(from: Option[TimeStamp])
  object FetchRequest {
    import TimeStampCodec._
    implicit val codec: Codec[FetchRequest] = deriveCodec[FetchRequest]
  }

  final case class FetchResponse(blocks: Seq[FetchEntry])
  object FetchResponse {
    implicit val codec: Codec[FetchResponse] = deriveCodec[FetchResponse]
  }

  final case class FetchEntry(
      hash: String,
      timestamp: TimeStamp,
      chainFrom: Int,
      chainTo: Int,
      height: Int,
      deps: AVector[String]
  )
  object FetchEntry {
    import TimeStampCodec._
    implicit val codec: Codec[FetchEntry] = deriveCodec[FetchEntry]

    def from(header: BlockHeader, height: Int)(implicit config: GroupConfig): FetchEntry = {
      FetchEntry(
        hash      = header.shortHex,
        timestamp = header.timestamp,
        chainFrom = header.chainIndex.from.value,
        chainTo   = header.chainIndex.to.value,
        height    = height,
        deps      = header.blockDeps.map(_.shortHex)
      )
    }

    def from(blockNotify: BlockNotify)(implicit config: GroupConfig): FetchEntry = {
      from(blockNotify.header, blockNotify.height)
    }
  }

  final case class PeersResult(cliques: AVector[CliqueInfo])
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

  final case class GetBalance(address: String, `type`: String)
  object GetBalance {
    implicit val codec: Codec[GetBalance] = deriveCodec[GetBalance]

    // TODO: refactor this once script system gets mature
    val pkh = "pkh"
  }

  final case class Balance(balance: BigInt, utxoNum: Int)
  object Balance {
    implicit val codec: Codec[Balance] = deriveCodec[Balance]
  }

  final case class Transfer(fromAddress: String,
                            fromType: String,
                            toAddress: String,
                            toType: String,
                            value: BigInt,
                            fromPrivateKey: String)
  object Transfer {
    implicit val codec: Codec[Transfer] = deriveCodec[Transfer]
  }

  final case class TransferResult(txId: String)
  object TransferResult {
    implicit val codec: Codec[TransferResult] = deriveCodec[TransferResult]
  }
}
