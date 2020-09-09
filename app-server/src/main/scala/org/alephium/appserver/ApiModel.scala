package org.alephium.appserver

import java.net.{InetAddress, InetSocketAddress}

import akka.util.ByteString
import io.circe._
import io.circe.generic.semiauto._

import org.alephium.crypto.Sha256
import org.alephium.flow.handler.FlowHandler.BlockNotify
import org.alephium.flow.network.InterCliqueManager
import org.alephium.flow.network.bootstrap.IntraCliqueInfo
import org.alephium.protocol.{Hash, PrivateKey, PublicKey, Signature}
import org.alephium.protocol.config.GroupConfig
import org.alephium.protocol.model._
import org.alephium.protocol.vm.LockupScript
import org.alephium.rpc.CirceUtils._
import org.alephium.serde.{serialize, RandomBytes}
import org.alephium.util._

sealed trait ApiModel

// scalastyle:off number.of.methods
// scalastyle:off number.of.types
object ApiModel {

  trait PerChain {
    val fromGroup: Int
    val toGroup: Int
  }

  final case class TimeInterval(from: TimeStamp, to: TimeStamp)

  final case class FetchRequest(fromTs: TimeStamp, toTs: TimeStamp) extends ApiModel

  final case class FetchResponse(blocks: Seq[BlockEntry]) extends ApiModel

  final case class OutputRef(scriptHint: Int, key: String)
  object OutputRef {
    def from(outputRef: TxOutputRef): OutputRef =
      OutputRef(outputRef.hint.value, outputRef.key.toHexString)
  }

  final case class Input(outputRef: OutputRef, unlockScript: ByteString)
  object Input {
    def from(input: TxInput): Input =
      Input(OutputRef.from(input.outputRef), serialize(input.unlockScript))
  }

  final case class Output(amount: Long, createdHeight: Int, address: Address)
  object Output {
    def from(output: TxOutput): Output =
      Output(output.amount.v.longValue, output.createdHeight, Address(output.lockupScript))
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
  }

  final case class NeighborCliques(cliques: AVector[InterCliqueInfo]) extends ApiModel
  object NeighborCliques {}

  final case class GetBalance(address: Address) extends ApiModel

  final case class GetGroup(address: Address) extends ApiModel

  final case class Balance(balance: U64, utxoNum: Int) extends ApiModel
  object Balance {
    def apply(balance_utxoNum: (U64, Int)): Balance = {
      Balance(balance_utxoNum._1, balance_utxoNum._2)
    }
  }

  final case class Group(group: Int) extends ApiModel

  final case class CreateTransaction(
      fromKey: PublicKey,
      toAddress: Address,
      value: U64
  ) extends ApiModel {
    def fromAddress: Address = Address(LockupScript.p2pkh(fromKey))
  }

  final case class CreateTransactionResult(unsignedTx: String,
                                           hash: String,
                                           fromGroup: Int,
                                           toGroup: Int)
      extends ApiModel
  object CreateTransactionResult {

    def from(unsignedTx: UnsignedTransaction)(
        implicit groupConfig: GroupConfig): CreateTransactionResult =
      CreateTransactionResult(Hex.toHexString(serialize(unsignedTx)),
                              Hex.toHexString(unsignedTx.hash.bytes),
                              unsignedTx.fromGroup.value,
                              unsignedTx.toGroup.value)
  }

  final case class SendTransaction(tx: String, signature: Signature) extends ApiModel

  final case class TxResult(txId: String, fromGroup: Int, toGroup: Int) extends ApiModel

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
  }

  final case class GetHashesAtHeight(val fromGroup: Int, val toGroup: Int, height: Int)
      extends ApiModel
      with PerChain

  final case class HashesAtHeight(headers: Seq[String]) extends ApiModel

  final case class GetChainInfo(val fromGroup: Int, val toGroup: Int) extends ApiModel with PerChain

  final case class ChainInfo(currentHeight: Int) extends ApiModel

  final case class GetBlock(hash: Hash) extends ApiModel

  sealed trait MinerAction

  object MinerAction {
    case object StartMining extends MinerAction
    case object StopMining  extends MinerAction
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

  }
}

trait ApiModelCodec {
  import ApiModel._

  implicit def apiConfig: ApiConfig
  implicit def networkType: NetworkType

  implicit val u64Encoder: Encoder[U64] = Encoder.encodeLong.contramap[U64](_.v)
  implicit val u64Decoder: Decoder[U64] = Decoder.decodeLong.map(U64.unsafe)
  implicit val u64Codec: Codec[U64]     = Codec.from(u64Decoder, u64Encoder)

  implicit val publicKeyEncoder: Encoder[PublicKey] = bytesEncoder
  implicit val publicKeyDecoder: Decoder[PublicKey] = bytesDecoder(PublicKey.from)
  implicit val publicKeyCodec: Codec[PublicKey] =
    Codec.from(publicKeyDecoder, publicKeyEncoder)

  implicit val privateKeyEncoder: Encoder[PrivateKey] = bytesEncoder
  implicit val privateKeyDecoder: Decoder[PrivateKey] = bytesDecoder(PrivateKey.from)

  implicit val signatureEncoder: Encoder[Signature] = bytesEncoder
  implicit val signatureDecoder: Decoder[Signature] = bytesDecoder(Signature.from)

  implicit val hashEncoder: Encoder[Hash] = hash => Json.fromString(hash.toHexString)
  implicit val hashDecoder: Decoder[Hash] =
    byteStringDecoder.emap(Hash.from(_).toRight("cannot decode hash"))
  implicit val hashCodec: Codec[Hash] = Codec.from(hashDecoder, hashEncoder)

  lazy val addressEncoder: Encoder[Address] =
    Encoder.encodeString.contramap[Address](_.toBase58(networkType))
  lazy val addressDecoder: Decoder[Address] =
    Decoder.decodeString.emap { input =>
      Address
        .fromBase58(input, networkType)
        .toRight(s"Unable to decode address from $input")
    }
  implicit lazy val addressCodec: Codec[Address] = Codec.from(addressDecoder, addressEncoder)

  implicit val cliqueIdEncoder: Encoder[CliqueId] = Encoder.encodeString.contramap(_.toHexString)
  implicit val cliqueIdDecoder: Decoder[CliqueId] = Decoder.decodeString.emap(createCliqueId)
  implicit val cliqueIdCodec: Codec[CliqueId]     = Codec.from(cliqueIdDecoder, cliqueIdEncoder)

  implicit val fetchResponseCodec: Codec[FetchResponse] = deriveCodec[FetchResponse]

  implicit val outputRefCodec: Codec[OutputRef] = deriveCodec[OutputRef]

  implicit val inputCodec: Codec[Input] = deriveCodec[Input]

  implicit val outputCodec: Codec[Output] = deriveCodec[Output]

  implicit val txCodec: Codec[Tx] = deriveCodec[Tx]

  implicit val blockEntryCodec: Codec[BlockEntry] = deriveCodec[BlockEntry]

  implicit val peerAddressCodec: Codec[PeerAddress] = deriveCodec[PeerAddress]

  implicit val selfCliqueCodec: Codec[SelfClique] = deriveCodec[SelfClique]

  implicit val neighborCliquesCodec: Codec[NeighborCliques] = deriveCodec[NeighborCliques]

  implicit val getBalanceCodec: Codec[GetBalance] = deriveCodec[GetBalance]

  implicit val getGroupCodec: Codec[GetGroup] = deriveCodec[GetGroup]

  implicit val balanceCodec: Codec[Balance] = deriveCodec[Balance]

  implicit val createTransactionCodec: Codec[CreateTransaction] = deriveCodec[CreateTransaction]

  implicit val groupCodec: Codec[Group] = deriveCodec[Group]

  implicit val createTransactionResultCodec: Codec[CreateTransactionResult] =
    deriveCodec[CreateTransactionResult]

  implicit val sendTransactionCodec: Codec[SendTransaction] = deriveCodec[SendTransaction]

  implicit val txResultCodec: Codec[TxResult] = deriveCodec[TxResult]

  implicit val getHashesAtHeightCodec: Codec[GetHashesAtHeight] = deriveCodec[GetHashesAtHeight]

  implicit val hashesAtHeightCodec: Codec[HashesAtHeight] = deriveCodec[HashesAtHeight]

  implicit val getChainInfoCodec: Codec[GetChainInfo] = deriveCodec[GetChainInfo]

  implicit val chainInfoCodec: Codec[ChainInfo] = deriveCodec[ChainInfo]

  implicit val getBlockCodec: Codec[GetBlock] = deriveCodec[GetBlock]

  implicit val minerActionDecoder: Decoder[MinerAction] = Decoder[String].emap {
    case "start-mining" => Right(MinerAction.StartMining)
    case "stop-mining"  => Right(MinerAction.StopMining)
    case other          => Left(s"Invalid miner action: $other")
  }
  implicit val minerActionEncoder: Encoder[MinerAction] = Encoder[String].contramap {
    case MinerAction.StartMining => "start-mining"
    case MinerAction.StopMining  => "stop-mining"
  }
  implicit val minerActionCodec: Codec[MinerAction] =
    Codec.from(minerActionDecoder, minerActionEncoder)

  implicit val apiKeyEncoder: Encoder[ApiKey] = Encoder.encodeString.contramap(_.value)
  implicit val apiKeyDecoder: Decoder[ApiKey] = Decoder.decodeString.emap(ApiKey.createApiKey)
  implicit val apiKeyCodec: Codec[ApiKey]     = Codec.from(apiKeyDecoder, apiKeyEncoder)

  implicit val cliqueEncoder: Encoder[InterCliqueInfo] =
    Encoder.forProduct3("id", "externalAddresses", "groupNumPerBroker")(info =>
      (info.id, info.externalAddresses, info.groupNumPerBroker))
  implicit val cliqueDecoder: Decoder[InterCliqueInfo] =
    Decoder.forProduct3("id", "externalAddresses", "groupNumPerBroker")(InterCliqueInfo.unsafe)

  implicit val interCliqueSyncedStatusCodec: Codec[InterCliquePeerInfo] =
    deriveCodec[InterCliquePeerInfo]

  lazy val fetchRequestDecoder: Decoder[FetchRequest] =
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
  val fetchRequestEncoder: Encoder[FetchRequest] = deriveEncoder[FetchRequest]
  implicit lazy val fetchRequestCodec: Codec[FetchRequest] =
    Codec.from(fetchRequestDecoder, fetchRequestEncoder)

  private def bytesEncoder[T <: RandomBytes]: Encoder[T] =
    Encoder.encodeString.contramap[T](_.toHexString)
  private def bytesDecoder[T](from: ByteString => Option[T]): Decoder[T] =
    Decoder.decodeString.emap { input =>
      val keyOpt = for {
        bs  <- Hex.from(input)
        key <- from(bs)
      } yield key
      keyOpt.toRight(s"Unable to decode key from $input")
    }

  private def createCliqueId(s: String): Either[String, CliqueId] = {
    Hex.from(s).flatMap(CliqueId.from) match {
      case Some(id) => Right(id)
      case None     => Left("invalid clique id")
    }
  }
}
