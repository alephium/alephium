package org.alephium.appserver

import java.net.{InetAddress, InetSocketAddress}

import akka.util.ByteString
import io.circe._
import io.circe.generic.semiauto._

import org.alephium.crypto.{ALFPrivateKey, ALFPublicKey, ALFSignature, Sha256}
import org.alephium.flow.handler.FlowHandler.BlockNotify
import org.alephium.flow.network.InterCliqueManager
import org.alephium.flow.network.bootstrap.IntraCliqueInfo
import org.alephium.protocol.Hash
import org.alephium.protocol.config.GroupConfig
import org.alephium.protocol.model._
import org.alephium.protocol.vm.LockupScript
import org.alephium.rpc.CirceUtils._
import org.alephium.serde.{deserialize, serialize, RandomBytes}
import org.alephium.util._

sealed trait ApiModel

// scalastyle:off number.of.methods
// scalastyle:off number.of.types
object ApiModel {
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
  implicit val publicKeyEncoder: Encoder[ALFPublicKey] = bytesEncoder
  implicit val publicKeyDecoder: Decoder[ALFPublicKey] = bytesDecoder(ALFPublicKey.from)
  implicit val publicKeyCodec: Codec[ALFPublicKey] =
    Codec.from(publicKeyDecoder, publicKeyEncoder)

  implicit val privateKeyEncoder: Encoder[ALFPrivateKey] = bytesEncoder
  implicit val privateKeyDecoder: Decoder[ALFPrivateKey] = bytesDecoder(ALFPrivateKey.from)

  implicit val signatureEncoder: Encoder[ALFSignature] = bytesEncoder
  implicit val signatureDecoder: Decoder[ALFSignature] = bytesDecoder(ALFSignature.from)

  implicit val hashEncoder: Encoder[Hash] = hash => Json.fromString(hash.toHexString)
  implicit val hashDecoder: Decoder[Hash] =
    byteStringDecoder.emap(Hash.from(_).toRight("cannot decode hash"))
  implicit val hashCodec: Codec[Hash] = Codec.from(hashDecoder, hashEncoder)

  type Address = LockupScript
  implicit val addressEncoder: Encoder[Address] =
    Encoder.encodeString.contramap[LockupScript](_.toBase58)
  implicit val addressDecoder: Decoder[Address] =
    Decoder.decodeString.emap { input =>
      val addressOpt = for {
        bs      <- Base58.decode(input)
        address <- deserialize[LockupScript](bs).toOption
      } yield address
      addressOpt.toRight(s"Unable to decode address from $input")
    }
  implicit val addressCodec: Codec[Address] = Codec.from(addressDecoder, addressEncoder)

  private def createCliqueId(s: String): Either[String, CliqueId] = {
    Hex.from(s).flatMap(CliqueId.from) match {
      case Some(id) => Right(id)
      case None     => Left("invalid clique id")
    }
  }

  implicit val cliqueIdEncoder: Encoder[CliqueId] = Encoder.encodeString.contramap(_.toHexString)
  implicit val cliqueIdDecoder: Decoder[CliqueId] = Decoder.decodeString.emap(createCliqueId)
  implicit val cliqueIdCodec: Codec[CliqueId]     = Codec.from(cliqueIdDecoder, cliqueIdEncoder)

  trait PerChain {
    val fromGroup: Int
    val toGroup: Int
  }

  object TimeStampCodec {
    implicit val decoderTS: Decoder[TimeStamp] =
      Decoder.decodeLong.ensure(_ >= 0, s"expect positive timestamp").map(TimeStamp.unsafe)
    implicit val encoderTS: Encoder[TimeStamp] = Encoder.encodeLong.contramap(_.millis)
    implicit val codec: Codec[TimeStamp]       = Codec.from(decoderTS, encoderTS)
  }

  final case class TimeInterval(from: TimeStamp, to: TimeStamp)

  final case class FetchRequest(fromTs: TimeStamp, toTs: TimeStamp) extends ApiModel
  object FetchRequest {
    def decoder(implicit apiConfig: ApiConfig): Decoder[FetchRequest] =
      deriveDecoder[FetchRequest]
        .ensure(
          fetchRequest => fetchRequest.fromTs <= fetchRequest.toTs,
          "`toTs` cannot be before `fromTs`"
        )
        .ensure(
          fetchRequest =>
            (fetchRequest.toTs -- fetchRequest.fromTs)
              .exists(_ <= apiConfig.blockflowFetchMaxAge),
          s"interval cannot be greater than ${apiConfig.blockflowFetchMaxAge}"
        )
    implicit val encoder: Encoder[FetchRequest] = deriveEncoder[FetchRequest]
    def codec(implicit apiConfig: ApiConfig): Codec[FetchRequest] =
      Codec.from(decoder, encoder)
  }

  final case class FetchResponse(blocks: Seq[BlockEntry]) extends ApiModel
  object FetchResponse {
    implicit val codec: Codec[FetchResponse] = deriveCodec[FetchResponse]
  }

  final case class OutputRef(scriptHint: Int, key: String)
  object OutputRef {
    def from(outputRef: TxOutputRef): OutputRef =
      OutputRef(outputRef.hint.value, outputRef.key.toHexString)
    implicit val codec: Codec[OutputRef] = deriveCodec[OutputRef]
  }

  final case class Input(outputRef: OutputRef, unlockScript: ByteString)
  object Input {
    def from(input: TxInput): Input =
      Input(OutputRef.from(input.outputRef), serialize(input.unlockScript))
    implicit val codec: Codec[Input] = deriveCodec[Input]
  }

  final case class Output(amount: Long, createdHeight: Int, address: Address)
  object Output {
    def from(output: TxOutput): Output =
      Output(output.amount.v.longValue, output.createdHeight, output.lockupScript)
    implicit val codec: Codec[Output] = deriveCodec[Output]
  }

  final case class Tx(
      hash: String,
      inputs: AVector[Input],
      outputs: AVector[Output]
  )
  object Tx {
    def from(tx: Transaction): Tx = Tx(
      tx.hash.toHexString,
      tx.unsigned.inputs.map(Input.from),
      tx.unsigned.fixedOutputs.map(Output.from) ++ tx.generatedOutputs.map(Output.from)
    )
    implicit val codec: Codec[Tx] = deriveCodec[Tx]
  }

  final case class BlockEntry(
      hash: String,
      timestamp: TimeStamp,
      chainFrom: Int,
      chainTo: Int,
      height: Int,
      deps: AVector[String],
      transactions: Option[AVector[Tx]]
  ) extends ApiModel
  object BlockEntry {
    implicit val codec: Codec[BlockEntry] = deriveCodec[BlockEntry]

    def from(header: BlockHeader, height: Int)(implicit config: GroupConfig): BlockEntry = {
      BlockEntry(
        hash         = header.hash.toHexString,
        timestamp    = header.timestamp,
        chainFrom    = header.chainIndex.from.value,
        chainTo      = header.chainIndex.to.value,
        height       = height,
        deps         = header.blockDeps.map(_.toHexString),
        transactions = None
      )
    }

    def from(block: Block, height: Int)(implicit config: GroupConfig): BlockEntry =
      from(block.header, height).copy(transactions = Some(block.transactions.map(Tx.from)))

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
      extends ApiModel
  object SelfClique {
    def from(cliqueInfo: IntraCliqueInfo): SelfClique = {
      SelfClique(cliqueInfo.id,
                 cliqueInfo.peers.map(peer =>
                   PeerAddress(peer.internalAddress.getAddress, peer.rpcPort, peer.wsPort)),
                 cliqueInfo.groupNumPerBroker)
    }
    implicit val codec: Codec[SelfClique] = deriveCodec[SelfClique]
  }

  final case class NeighborCliques(cliques: AVector[InterCliqueInfo]) extends ApiModel
  object NeighborCliques {
    implicit val cliqueEncoder: Encoder[InterCliqueInfo] =
      Encoder.forProduct3("id", "externalAddresses", "groupNumPerBroker")(info =>
        (info.id, info.externalAddresses, info.groupNumPerBroker))
    implicit val cliqueDecoder: Decoder[InterCliqueInfo] =
      Decoder.forProduct3("id", "externalAddresses", "groupNumPerBroker")(InterCliqueInfo.unsafe)
    implicit val codec: Codec[NeighborCliques] = deriveCodec[NeighborCliques]
  }

  final case class GetBalance(address: Address) extends ApiModel
  object GetBalance {
    implicit val codec: Codec[GetBalance] = deriveCodec[GetBalance]
  }

  final case class GetGroup(address: Address) extends ApiModel
  object GetGroup {
    implicit val codec: Codec[GetGroup] = deriveCodec[GetGroup]
  }

  final case class Balance(balance: U64, utxoNum: Int) extends ApiModel
  object Balance {
    implicit val codec: Codec[Balance] = deriveCodec[Balance]
    def apply(balance_utxoNum: (U64, Int)): Balance = {
      Balance(balance_utxoNum._1, balance_utxoNum._2)
    }
  }

  final case class Group(group: Int) extends ApiModel
  object Group {
    implicit val codec: Codec[Group] = deriveCodec[Group]
  }

  final case class CreateTransaction(
      fromKey: ALFPublicKey,
      toAddress: Address,
      value: U64
  ) extends ApiModel {
    def fromAddress: Address = LockupScript.p2pkh(fromKey)
  }
  object CreateTransaction {
    implicit val codec: Codec[CreateTransaction] = deriveCodec[CreateTransaction]
  }

  final case class CreateTransactionResult(unsignedTx: String, hash: String) extends ApiModel
  object CreateTransactionResult {
    implicit val codec: Codec[CreateTransactionResult] = deriveCodec[CreateTransactionResult]

    def from(unsignedTx: UnsignedTransaction): CreateTransactionResult =
      CreateTransactionResult(Hex.toHexString(serialize(unsignedTx)),
                              Hex.toHexString(unsignedTx.hash.bytes))
  }

  final case class SendTransaction(tx: String, signature: ALFSignature) extends ApiModel
  object SendTransaction {
    implicit val codec: Codec[SendTransaction] = deriveCodec[SendTransaction]
  }

  final case class TxResult(txId: String, fromGroup: Int, toGroup: Int) extends ApiModel
  object TxResult {
    implicit val codec: Codec[TxResult] = deriveCodec[TxResult]
  }

  final case class InterCliquePeerInfo(cliqueId: CliqueId,
                                       brokerId: Int,
                                       address: InetSocketAddress,
                                       isSynced: Boolean)
      extends ApiModel
  object InterCliquePeerInfo {
    def from(syncStatus: InterCliqueManager.SyncStatus): InterCliquePeerInfo = {
      val peerId = syncStatus.peerId
      InterCliquePeerInfo(peerId.cliqueId, peerId.brokerId, syncStatus.address, syncStatus.isSynced)
    }
    implicit val interCliqueSyncedStatusCodec: Codec[InterCliquePeerInfo] =
      deriveCodec[InterCliquePeerInfo]
  }

  final case class GetHashesAtHeight(val fromGroup: Int, val toGroup: Int, height: Int)
      extends ApiModel
      with PerChain
  object GetHashesAtHeight {
    implicit val codec: Codec[GetHashesAtHeight] = deriveCodec[GetHashesAtHeight]
  }

  final case class HashesAtHeight(headers: Seq[String]) extends ApiModel
  object HashesAtHeight {
    implicit val codec: Codec[HashesAtHeight] = deriveCodec[HashesAtHeight]
  }

  final case class GetChainInfo(val fromGroup: Int, val toGroup: Int) extends ApiModel with PerChain
  object GetChainInfo {
    implicit val codec: Codec[GetChainInfo] = deriveCodec[GetChainInfo]
  }

  final case class ChainInfo(currentHeight: Int) extends ApiModel
  object ChainInfo {
    implicit val codec: Codec[ChainInfo] = deriveCodec[ChainInfo]
  }

  final case class GetBlock(hash: Hash) extends ApiModel
  object GetBlock {
    implicit val codec: Codec[GetBlock] = deriveCodec[GetBlock]
  }

  sealed trait MinerAction

  object MinerAction {
    case object StartMining extends MinerAction
    case object StopMining  extends MinerAction

    implicit val decoder: Decoder[MinerAction] = Decoder[String].emap {
      case "start-mining" => Right(StartMining)
      case "stop-mining"  => Right(StopMining)
      case other          => Left(s"Invalid miner action: $other")
    }

    implicit val encoder: Encoder[MinerAction] = Encoder[String].contramap {
      case StartMining => "start-mining"
      case StopMining  => "stop-mining"
    }

    implicit val codec: Codec[MinerAction] = Codec.from(decoder, encoder)
  }

  final case class ApiKey private (val value: String) {
    def hash: Sha256 = Sha256.hash(value)
  }

  object ApiKey {
    def unsafe(raw: String): ApiKey = new ApiKey(raw)
    def createApiKey(raw: String): Either[String, ApiKey] = {
      if (raw.length < 32) {
        Left("Api key must have at least 32 characters")
      } else {
        Right(new ApiKey(raw))
      }
    }

    implicit val encoder: Encoder[ApiKey] = Encoder.encodeString.contramap(_.value)
    implicit val decoder: Decoder[ApiKey] = Decoder.decodeString.emap(createApiKey)
    implicit val codec: Codec[ApiKey]     = Codec.from(decoder, encoder)
  }
}
