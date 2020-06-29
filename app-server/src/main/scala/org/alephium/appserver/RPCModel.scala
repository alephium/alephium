package org.alephium.appserver

import java.net.{InetAddress, InetSocketAddress}

import akka.util.ByteString
import io.circe._
import io.circe.generic.semiauto._

import org.alephium.crypto.{ED25519PrivateKey, ED25519PublicKey, ED25519Signature}
import org.alephium.flow.core.FlowHandler.BlockNotify
import org.alephium.flow.network.InterCliqueManager
import org.alephium.flow.network.bootstrap.IntraCliqueInfo
import org.alephium.protocol.config.GroupConfig
import org.alephium.protocol.model.{BlockHeader, CliqueId, CliqueInfo, UnsignedTransaction}
import org.alephium.protocol.vm.LockupScript
import org.alephium.rpc.CirceUtils._
import org.alephium.serde.{deserialize, serialize, RandomBytes}
import org.alephium.util.{AVector, Base58, Hex, TimeStamp, U64}

sealed trait RPCModel

// scalastyle:off number.of.methods
object RPCModel {
  implicit val u64Encoder: Encoder[U64] = Encoder.encodeLong.contramap[U64](_.v)
  implicit val u64Decoder: Decoder[U64] = Decoder.decodeLong.map(U64.unsafe)
  implicit val u64Codec: Codec[U64]     = Codec.from(u64Decoder, u64Encoder)

  def bytesEncoder[T <: RandomBytes]: Encoder[T] = Encoder.encodeString.contramap[T](_.toHexString)
  def bytesDecoder[T](from: ByteString => Option[T]): Decoder[T] =
    Decoder.decodeString.emap { input =>
      val keyOpt = for {
        bs  <- Hex.from(input)
        key <- from(bs)
      } yield key
      keyOpt.toRight(s"Unable to decode key from $input")
    }
  implicit val publicKeyEncoder: Encoder[ED25519PublicKey]   = bytesEncoder
  implicit val publicKeyDecoder: Decoder[ED25519PublicKey]   = bytesDecoder(ED25519PublicKey.from)
  implicit val privateKeyEncoder: Encoder[ED25519PrivateKey] = bytesEncoder
  implicit val privateKeyDecoder: Decoder[ED25519PrivateKey] = bytesDecoder(ED25519PrivateKey.from)
  implicit val signatureEncoder: Encoder[ED25519Signature]   = bytesEncoder
  implicit val signatureDecoder: Decoder[ED25519Signature]   = bytesDecoder(ED25519Signature.from)

  type Address = LockupScript
  implicit val addressEncoder: Encoder[Address] =
    Encoder.encodeString.contramap[LockupScript](script => Base58.encode(serialize(script)))
  implicit val addressDecoder: Decoder[Address] =
    Decoder.decodeString.emap { input =>
      val addressOpt = for {
        bs      <- Base58.decode(input)
        address <- deserialize[LockupScript](bs).toOption
      } yield address
      addressOpt.toRight(s"Unable to decode address from $input")
    }

  object TimeStampCodec {
    implicit val decoderTS: Decoder[TimeStamp] =
      Decoder.decodeLong.ensure(_ >= 0, s"expect positive timestamp").map(TimeStamp.unsafe)
    implicit val encoderTS: Encoder[TimeStamp] = Encoder.encodeLong.contramap(_.millis)
    implicit val codec: Codec[TimeStamp]       = Codec.from(decoderTS, encoderTS)
  }

  final case class FetchRequest(fromTs: TimeStamp, toTs: TimeStamp) extends RPCModel
  object FetchRequest {
    import TimeStampCodec._
    def decoder(implicit rpcConfig: RPCConfig): Decoder[FetchRequest] =
      deriveDecoder[FetchRequest]
        .ensure(
          fetchRequest => fetchRequest.fromTs <= fetchRequest.toTs,
          "`toTs` cannot be before `fromTs`"
        )
        .ensure(
          fetchRequest =>
            (fetchRequest.toTs -- fetchRequest.fromTs)
              .exists(_ <= rpcConfig.blockflowFetchMaxAge),
          s"interval cannot be greater than ${rpcConfig.blockflowFetchMaxAge}"
        )
    implicit val encoder: Encoder[FetchRequest] = deriveEncoder[FetchRequest]
    def codec(implicit rpcConfig: RPCConfig): Codec[FetchRequest] =
      Codec.from(decoder, encoder)
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

  final case class PeerAddress(address: InetAddress, rpcPort: Option[Int], wsPort: Option[Int])
  object PeerAddress {
    implicit val codec: Codec[PeerAddress] = deriveCodec[PeerAddress]
  }

  final case class SelfClique(cliqueId: CliqueId,
                              peers: AVector[PeerAddress],
                              groupNumPerBroker: Int)
      extends RPCModel
  object SelfClique {
    import CliqueIdCodec._
    def from(cliqueInfo: IntraCliqueInfo): SelfClique = {
      SelfClique(cliqueInfo.id,
                 cliqueInfo.peers.map(peer => PeerAddress(peer.address, peer.rpcPort, peer.wsPort)),
                 cliqueInfo.groupNumPerBroker)
    }

    implicit val codec: Codec[SelfClique] = deriveCodec[SelfClique]
  }

  final case class NeighborCliques(cliques: AVector[CliqueInfo]) extends RPCModel
  object NeighborCliques {
    import CliqueIdCodec._
    implicit val cliqueEncoder: Encoder[CliqueInfo] =
      Encoder.forProduct3("id", "peers", "groupNumPerBroker")(info =>
        (info.id, info.peers, info.groupNumPerBroker))
    implicit val cliqueDecoder: Decoder[CliqueInfo] =
      Decoder.forProduct3("id", "peers", "groupNumPerBroker")(CliqueInfo.unsafe)
    implicit val codec: Codec[NeighborCliques] = deriveCodec[NeighborCliques]
  }

  final case class GetBalance(address: Address) extends RPCModel
  object GetBalance {
    implicit val codec: Codec[GetBalance] = deriveCodec[GetBalance]
  }

  final case class GetGroup(address: Address) extends RPCModel
  object GetGroup {
    implicit val codec: Codec[GetGroup] = deriveCodec[GetGroup]
  }

  final case class Balance(balance: U64, utxoNum: Int) extends RPCModel
  object Balance {
    implicit val codec: Codec[Balance] = deriveCodec[Balance]
    def apply(balance_utxoNum: (U64, Int)): Balance = {
      Balance(balance_utxoNum._1, balance_utxoNum._2)
    }
  }

  final case class Group(group: Int) extends RPCModel
  object Group {
    implicit val codec: Codec[Group] = deriveCodec[Group]
  }

  final case class CreateTransaction(
      fromKey: ED25519PublicKey,
      toKey: ED25519PublicKey,
      value: U64
  ) extends RPCModel {
    def fromAddress: Address = LockupScript.p2pkh(fromKey)
    def toAddress: Address   = LockupScript.p2pkh(toKey)
  }
  object CreateTransaction {
    implicit val codec: Codec[CreateTransaction] = deriveCodec[CreateTransaction]
  }

  final case class CreateTransactionResult(unsignedTx: String, hash: String) extends RPCModel
  object CreateTransactionResult {
    implicit val codec: Codec[CreateTransactionResult] = deriveCodec[CreateTransactionResult]

    def from(unsignedTx: UnsignedTransaction): CreateTransactionResult =
      CreateTransactionResult(Hex.toHexString(serialize(unsignedTx)),
                              Hex.toHexString(unsignedTx.hash.bytes))
  }

  final case class SendTransaction(tx: String, signature: ED25519Signature) extends RPCModel
  object SendTransaction {
    implicit val codec: Codec[SendTransaction] = deriveCodec[SendTransaction]
  }

  final case class TxResult(txId: String, fromGroup: Int, toGroup: Int) extends RPCModel
  object TxResult {
    implicit val codec: Codec[TxResult] = deriveCodec[TxResult]
  }

  final case class InterCliquePeerInfo(cliqueId: CliqueId,
                                       address: InetSocketAddress,
                                       isSynced: Boolean)
      extends RPCModel
  object InterCliquePeerInfo {
    import CliqueIdCodec._

    def from(syncStatus: InterCliqueManager.SyncStatus): InterCliquePeerInfo =
      InterCliquePeerInfo(syncStatus.cliqueId, syncStatus.address, syncStatus.isSynced)

    implicit val interCliqueSyncedStatusCodec: Codec[InterCliquePeerInfo] =
      deriveCodec[InterCliquePeerInfo]
  }

  object CliqueIdCodec {
    def createId(s: String): Either[String, CliqueId] = {
      Hex.from(s).flatMap(CliqueId.from) match {
        case Some(id) => Right(id)
        case None     => Left("invalid clique id")
      }
    }

    implicit val idEncoder: Encoder[CliqueId] = Encoder.encodeString.contramap(_.toHexString)
    implicit val idDecoder: Decoder[CliqueId] = Decoder.decodeString.emap(createId)
  }
}
