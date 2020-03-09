package org.alephium.appserver

import java.net.InetSocketAddress

import io.circe._
import io.circe.generic.semiauto._

import org.alephium.flow.core.FlowHandler.BlockNotify
import org.alephium.protocol.config.GroupConfig
import org.alephium.protocol.model.{BlockHeader, CliqueId, CliqueInfo}
import org.alephium.rpc.CirceUtils._
import org.alephium.util.{AVector, Hex, TimeStamp}

sealed trait RPCModel

object RPCModel {
  object TimeStampCodec {
    implicit val decoderTS: Decoder[TimeStamp] =
      Decoder.decodeLong.ensure(_ >= 0, s"expect positive timestamp").map(TimeStamp.unsafe)
    implicit val encoderTS: Encoder[TimeStamp] = Encoder.encodeLong.contramap(_.millis)
  }

  final case class FetchRequest(from: Option[TimeStamp]) extends RPCModel
  object FetchRequest {
    import TimeStampCodec._
    implicit val codec: Codec[FetchRequest] = deriveCodec[FetchRequest]
  }

  final case class FetchResponse(blocks: Seq[BlockEntry]) extends RPCModel
  object FetchResponse {
    implicit val codec: Codec[FetchResponse] = deriveCodec[FetchResponse]
  }

  final case class BlockEntry(
      hash: String,
      timestamp: TimeStamp,
      chainFrom: Int,
      chainTo: Int,
      height: Int,
      deps: AVector[String]
  ) extends RPCModel
  object BlockEntry {
    import TimeStampCodec._
    implicit val codec: Codec[BlockEntry] = deriveCodec[BlockEntry]

    def from(header: BlockHeader, height: Int)(implicit config: GroupConfig): BlockEntry = {
      BlockEntry(
        hash      = header.shortHex,
        timestamp = header.timestamp,
        chainFrom = header.chainIndex.from.value,
        chainTo   = header.chainIndex.to.value,
        height    = height,
        deps      = header.blockDeps.map(_.shortHex)
      )
    }

    def from(blockNotify: BlockNotify)(implicit config: GroupConfig): BlockEntry = {
      from(blockNotify.header, blockNotify.height)
    }
  }

  final case class SelfClique(peers: AVector[InetSocketAddress], groupNumPerBroker: Int)
      extends RPCModel
  object SelfClique {
    def from(cliqueInfo: CliqueInfo): SelfClique =
      SelfClique(cliqueInfo.peers, cliqueInfo.groupNumPerBroker)

    implicit val codec: Codec[SelfClique] = deriveCodec[SelfClique]
  }

  final case class PeerCliques(cliques: AVector[CliqueInfo]) extends RPCModel
  object PeerCliques {
    def createId(s: String): Either[String, CliqueId] = {
      Hex.from(s).flatMap(CliqueId.from) match {
        case Some(id) => Right(id)
        case None     => Left("invalid clique id")
      }
    }

    implicit val idEncoder: Encoder[CliqueId]       = Encoder.encodeString.contramap(_.toHexString)
    implicit val idDecoder: Decoder[CliqueId]       = Decoder.decodeString.emap(createId)
    implicit val cliqueInfoCodec: Codec[CliqueInfo] = deriveCodec[CliqueInfo]
    implicit val codec: Codec[PeerCliques]          = deriveCodec[PeerCliques]
  }

  final case class GetBalance(address: String, `type`: String) extends RPCModel
  object GetBalance {
    implicit val codec: Codec[GetBalance] = deriveCodec[GetBalance]

    // TODO: refactor this once script system gets mature
    val pkh = "pkh"
  }

  final case class Balance(balance: BigInt, utxoNum: Int) extends RPCModel
  object Balance {
    implicit val codec: Codec[Balance] = deriveCodec[Balance]
  }

  final case class Transfer(fromAddress: String,
                            fromType: String,
                            toAddress: String,
                            toType: String,
                            value: BigInt,
                            fromPrivateKey: String)
      extends RPCModel
  object Transfer {
    implicit val codec: Codec[Transfer] = deriveCodec[Transfer]
  }

  final case class TransferResult(txId: String) extends RPCModel
  object TransferResult {
    implicit val codec: Codec[TransferResult] = deriveCodec[TransferResult]
  }
}
