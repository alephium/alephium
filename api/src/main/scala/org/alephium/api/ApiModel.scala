// Copyright 2018 The Alephium Authors
// This file is part of the alephium project.
//
// The library is free software: you can redistribute it and/or modify
// it under the terms of the GNU Lesser General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// The library is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
// GNU Lesser General Public License for more details.
//
// You should have received a copy of the GNU Lesser General Public License
// along with the library. If not, see <http://www.gnu.org/licenses/>.

package org.alephium.api

import java.net.InetSocketAddress

import scala.util.{Failure, Success, Try}

import akka.util.ByteString
import upickle.core.Abort

import org.alephium.api.UtilJson._
import org.alephium.api.model._
import org.alephium.crypto.wallet.Mnemonic
import org.alephium.json.Json._
import org.alephium.json.Json.{ReadWriter => RW}
import org.alephium.protocol.{Hash, PublicKey, Signature}
import org.alephium.protocol.config.GroupConfig
import org.alephium.protocol.model
import org.alephium.protocol.model.{
  Address,
  AddressLike,
  BlockHash,
  CliqueId,
  ContractId,
  GroupIndex,
  NetworkId,
  Nonce,
  TokenId,
  TransactionId
}
import org.alephium.protocol.vm.{GasBox, GasPrice, LockupScript, StatefulContract}
import org.alephium.serde.{deserialize, serialize, RandomBytes}
import org.alephium.util._

// scalastyle:off number.of.methods
// scalastyle:off number.of.types
object ApiModel {
  trait PerChain {
    val fromGroup: Int
    val toGroup: Int
  }
}

@SuppressWarnings(Array("org.wartremover.warts.ToString"))
trait ApiModelCodec {

  implicit def groupConfig: GroupConfig

  implicit val peerStatusBannedRW: RW[PeerStatus.Banned]   = macroRW
  implicit val peerStatusPenaltyRW: RW[PeerStatus.Penalty] = macroRW

  implicit val peerStatusRW: RW[PeerStatus] = RW.merge(peerStatusBannedRW, peerStatusPenaltyRW)

  implicit val peerMisbehaviorRW: RW[PeerMisbehavior] = macroRW

  implicit val u256Writer: Writer[U256] = javaBigIntegerWriter.comap[U256](_.toBigInt)
  implicit val u256Reader: Reader[U256] = javaBigIntegerReader.map { u256 =>
    U256.from(u256).getOrElse(throw new Abort(s"Invalid U256: $u256"))
  }

  implicit val i256Writer: Writer[I256] = javaBigIntegerWriter.comap[I256](_.toBigInt)
  implicit val i256Reader: Reader[I256] = javaBigIntegerReader.map { i256 =>
    I256.from(i256).getOrElse(throw new Abort(s"Invalid I256: $i256"))
  }
  implicit val nonceWriter: Writer[Nonce] = byteStringWriter.comap[Nonce](_.value)
  implicit val nonceReader: Reader[Nonce] = byteStringReader.map { bytes =>
    Nonce.from(bytes).getOrElse(throw Abort(s"Invalid nonce: $bytes"))
  }

  implicit val gasBoxWriter: Writer[GasBox] = implicitly[Writer[Int]].comap(_.value)
  implicit val gasBoxReader: Reader[GasBox] = implicitly[Reader[Int]].map { value =>
    GasBox.from(value).getOrElse(throw new Abort(s"Invalid Gas: $value"))
  }

  implicit val gasPriceWriter: Writer[GasPrice] = u256Writer.comap(_.value)
  implicit val gasPriceReader: Reader[GasPrice] = u256Reader.map(GasPrice.apply)

  implicit val amountWriter: Writer[Amount] = javaBigIntegerWriter.comap[Amount](_.value.toBigInt)
  implicit val amountReader: Reader[Amount] = StringReader.map { input =>
    Try(new java.math.BigInteger(input)) match {
      case Success(bigInt) =>
        Amount(U256.from(bigInt).getOrElse(throw new Abort(s"Invalid amount: $bigInt")))
      case Failure(_) =>
        Amount.from(input).getOrElse(throw new Abort(s"Invalid amount: $input"))
    }
  }

  implicit def groupIndexRW(implicit groupConfig: GroupConfig): RW[GroupIndex] =
    readwriter[Int].bimap(
      _.value,
      group => GroupIndex.from(group).getOrElse(throw Abort(s"Invalid group index : $group"))
    )

  implicit val amountHintReader: Reader[Amount.Hint] = amountReader.map(_.hint)
  implicit val amountHintWriter: Writer[Amount.Hint] = StringWriter.comap[Amount.Hint] { amount =>
    Amount.toAlphString(amount.value)
  }

  implicit val publicKeyWriter: Writer[PublicKey] = bytesWriter
  implicit val publicKeyReader: Reader[PublicKey] = bytesReader(PublicKey.from)

  implicit val signatureWriter: Writer[Signature] = bytesWriter
  implicit val signatureReader: Reader[Signature] = bytesReader(Signature.from)

  implicit val hashWriter: Writer[Hash] = StringWriter.comap[Hash](
    _.toHexString
  )
  implicit val hashReader: Reader[Hash] =
    byteStringReader.map(Hash.from(_).getOrElse(throw new Abort("cannot decode 32 bytes hash")))

  implicit val blockHashWriter: Writer[BlockHash] = StringWriter.comap[BlockHash](
    _.toHexString
  )
  implicit val blockHashReader: Reader[BlockHash] =
    byteStringReader.map(BlockHash.from(_).getOrElse(throw new Abort("cannot decode block hash")))

  implicit val tokenIdWriter: Writer[TokenId] = hashWriter.comap[TokenId](_.value)
  implicit val tokenIdReader: Reader[TokenId] = hashReader.map(TokenId.unsafe(_))

  implicit val contractIdWriter: Writer[ContractId] = hashWriter.comap[ContractId](_.value)
  implicit val contractIdReader: Reader[ContractId] = hashReader.map(ContractId.unsafe(_))

  implicit val transactionIdWriter: Writer[TransactionId] = hashWriter.comap[TransactionId](_.value)
  implicit val transactionIdReader: Reader[TransactionId] = hashReader.map(TransactionId.unsafe(_))

  implicit lazy val assetAddressWriter: Writer[Address.Asset] =
    StringWriter.comap[Address.Asset](_.toBase58)
  implicit lazy val assetAddressReader: Reader[Address.Asset] = addressReader.map {
    case address: Address.Asset => address
    case address: Address.Contract =>
      throw Abort(s"Expect asset address, but was contract address: ${address.toBase58}")
  }

  implicit lazy val contractAddressRW: RW[Address.Contract] = readwriter[String].bimap(
    _.toBase58,
    input =>
      Address.fromBase58(input) match {
        case Some(address: Address.Contract) => address
        case Some(_: Address.Asset) =>
          throw Abort(s"Expect contract address, but was asset address: $input")
        case None =>
          throw Abort(s"Unable to decode address from $input")
      }
  )

  implicit lazy val addressWriter: Writer[Address] = StringWriter.comap[Address](_.toBase58)
  implicit lazy val addressReader: Reader[Address] = StringReader.map { input =>
    LockupScript.decodeFromBase58(input) match {
      case LockupScript.CompleteLockupScript(lockupScript) => Address.from(lockupScript)
      case LockupScript.HalfDecodedP2PK(publicKey) =>
        val groupIndex = publicKey.defaultGroup
        Address.Asset(LockupScript.p2pk(publicKey, groupIndex))
      case LockupScript.InvalidLockupScript =>
        throw Abort(s"Unable to decode address from $input")
    }
  }

  implicit lazy val addressLikeWriter: Writer[AddressLike] =
    StringWriter.comap[AddressLike](_.toBase58)
  implicit lazy val addressLikeReader: Reader[AddressLike] = StringReader.map { input =>
    AddressLike.fromBase58(input) match {
      case Some(addressLike) => addressLike
      case None              => throw Abort(s"Unable to decode address from $input")
    }
  }

  implicit val cliqueIdWriter: Writer[CliqueId] = StringWriter.comap[CliqueId](_.toHexString)
  implicit val cliqueIdReader: Reader[CliqueId] = StringReader.map { s =>
    Hex.from(s).flatMap(CliqueId.from).getOrElse(throw new Abort("invalid clique id"))
  }

  implicit val networkIdWriter: Writer[NetworkId] = ByteWriter.comap[NetworkId](_.id)
  implicit val networkIdReader: Reader[NetworkId] = ByteReader.map(NetworkId(_))

  implicit val hashrateResponseRW: RW[HashRateResponse] = macroRW

  implicit val currentDifficultyRW: RW[CurrentDifficulty] = macroRW

  implicit val blocksPerTimeStampRangeRW: RW[BlocksPerTimeStampRange] = macroRW

  implicit val blocksAndEventsPerTimeStampRangeRW: RW[BlocksAndEventsPerTimeStampRange] = macroRW
  implicit val richBlocksAndEventsPerTimeStampRangeRW: RW[RichBlocksAndEventsPerTimeStampRange] =
    macroRW

  implicit val mempoolTransactionsRW: RW[MempoolTransactions] = macroRW

  implicit val outputRefRW: RW[OutputRef] = macroRW

  implicit val tokenRW: RW[Token] = macroRW

  implicit val scriptRW: RW[Script] = readwriter[String].bimap(
    _.value,
    Script(_)
  )

  implicit val outputAssetRW: RW[AssetOutput]       = macroRW[AssetOutput]
  implicit val outputContractRW: RW[ContractOutput] = macroRW[ContractOutput]

  implicit val fixedAssetOutputRW: RW[FixedAssetOutput] = macroRW[FixedAssetOutput]

  implicit val outputRW: RW[Output] =
    RW.merge(outputAssetRW, outputContractRW)

  implicit val inputAssetRW: RW[AssetInput] = macroRW[AssetInput]

  implicit val unsignedTxRW: RW[UnsignedTx] = macroRW

  implicit val transactionTemplateRW: RW[TransactionTemplate] = macroRW

  implicit val transactionRW: RW[Transaction] = macroRW

  implicit val richAssetInputRW: RW[RichAssetInput]       = macroRW
  implicit val richContractInputRW: RW[RichContractInput] = macroRW
  implicit val richInputRW: RW[RichInput]                 = macroRW

  implicit val richUnsignedTxRW: RW[RichUnsignedTx]   = macroRW
  implicit val richTransactionRW: RW[RichTransaction] = macroRW

  implicit val rawTransactionRW: RW[RawTransaction] = macroRW

  implicit val exportFileRW: RW[ExportFile] = macroRW

  implicit val ghostUncleBlockEntryRW: RW[GhostUncleBlockEntry] = macroRW

  implicit val blockEntryRW: RW[BlockEntry] = macroRW

  implicit val richBlockEntryRW: RW[RichBlockEntry] = macroRW

  implicit val blockAndEventsRW: RW[BlockAndEvents]         = macroRW
  implicit val richBlockAndEventsRW: RW[RichBlockAndEvents] = macroRW

  implicit val blockHeaderEntryRW: RW[BlockHeaderEntry] = macroRW

  implicit val rawBlockRW: RW[RawBlock] = macroRW

  implicit val blockCandidateRW: RW[BlockCandidate] = macroRW

  implicit val blockSolutionRW: RW[BlockSolution] = macroRW

  implicit val peerAddressRW: RW[PeerAddress] = macroRW

  implicit val nodeInfoRW: RW[NodeInfo] = macroRW

  implicit val nodeVersionRW: RW[NodeVersion] = macroRW

  implicit val buildInfoRW: RW[NodeInfo.BuildInfo] = macroRW

  implicit val chainParamsRW: RW[ChainParams] = macroRW

  implicit val selfCliqueRW: RW[SelfClique] = macroRW

  implicit val neighborPeersRW: RW[NeighborPeers] = macroRW

  implicit val getBalanceRW: RW[GetBalance] = macroRW

  implicit val getGroupRW: RW[GetGroup] = macroRW

  implicit val balanceRW: RW[Balance] = macroRW

  implicit val utxoRW: RW[UTXO] = macroRW

  implicit val utxosRW: RW[UTXOs] = macroRW

  implicit val destinationRW: RW[Destination] = macroRW

  implicit val fromPublicKeyTypeRW: RW[BuildTxCommon.PublicKeyType] = readwriter[String].bimap(
    {
      case BuildTxCommon.Default       => "default"
      case BuildTxCommon.BIP340Schnorr => "bip340-schnorr"
      case BuildTxCommon.GLSecP256K1   => "gl-secp256k1"
      case BuildTxCommon.GLSecP256R1   => "gl-secp256r1"
      case BuildTxCommon.GLED25519     => "gl-ed25519"
      case BuildTxCommon.GLWebAuthn    => "gl-webauthn"
    },
    {
      case "default"        => BuildTxCommon.Default
      case "bip340-schnorr" => BuildTxCommon.BIP340Schnorr
      case "gl-secp256k1"   => BuildTxCommon.GLSecP256K1
      case "gl-secp256r1"   => BuildTxCommon.GLSecP256R1
      case "gl-ed25519"     => BuildTxCommon.GLED25519
      case "gl-webauthn"    => BuildTxCommon.GLWebAuthn
      case other            => throw Abort(s"Invalid public key type: $other")
    }
  )

  implicit val buildMultiAddressesTransactionSourceRW: RW[BuildMultiAddressesTransaction.Source] =
    macroRW

  implicit val buildMultiAddressesTransactionRW: RW[BuildMultiAddressesTransaction] = macroRW

  implicit val buildSweepAddressTransactionsRW: RW[BuildSweepAddressTransactions] = macroRW

  implicit val groupRW: RW[Group] = macroRW

  implicit val sweepAddressTransactionRW: RW[SweepAddressTransaction] = macroRW

  implicit val buildSweepAddressTransactionsResultRW: RW[BuildSweepAddressTransactionsResult] =
    macroRW

  implicit val submitTransactionRW: RW[SubmitTransaction] = macroRW

  implicit val decodeTransactionRW: RW[DecodeUnsignedTx]             = macroRW
  implicit val decodeTransactionResultRW: RW[DecodeUnsignedTxResult] = macroRW

  implicit val txStatusRW: RW[TxStatus] =
    RW.merge(macroRW[Confirmed], macroRW[MemPooled], macroRW[TxNotFound])

  implicit val buildTransferRW: RW[BuildTransferTx]                                       = macroRW
  implicit val buildDeployContractTxRW: RW[BuildDeployContractTx]                         = macroRW
  implicit val buildExecuteScriptTxRW: RW[BuildExecuteScriptTx]                           = macroRW
  implicit val buildSimpleTransferResultRW: RW[BuildSimpleTransferTxResult]               = macroRW
  implicit val buildSimpleDeployContractTxResultRW: RW[BuildSimpleDeployContractTxResult] = macroRW
  implicit val buildSimpleExecuteScriptTxResultRW: RW[BuildSimpleExecuteScriptTxResult]   = macroRW

  implicit val buildTransactionTransferRW: RW[BuildChainedTransferTx] = macroRW
  implicit val buildTransactionDeployContractRW: RW[BuildChainedDeployContractTx] =
    macroRW
  implicit val buildTransactionExecuteScriptRW: RW[BuildChainedExecuteScriptTx] = macroRW
  implicit val buildTransactionRW: RW[BuildChainedTx]                           = macroRW
  implicit val buildTransactionTransferResultRW: RW[BuildChainedTransferTxResult] =
    macroRW
  implicit val buildTransactionDeployContractResultRW: RW[BuildChainedDeployContractTxResult] =
    macroRW
  implicit val buildTransactionExecuteScriptResultRW: RW[BuildChainedExecuteScriptTxResult] =
    macroRW
  implicit val buildChainedTxResultRW: RW[BuildChainedTxResult] = macroRW

  implicit val buildMultisigAddressRW: RW[BuildMultisigAddress] = macroRW

  implicit val buildMultisigAddressResultRW: RW[BuildMultisigAddressResult] = macroRW

  implicit val buildMultisigRW: RW[BuildMultisig] = macroRW

  implicit val buildSweepMultisigRW: RW[BuildSweepMultisig] = macroRW

  implicit val submitMultisigTransactionRW: RW[SubmitMultisig] = macroRW

  implicit val compilerOptionsRW: RW[CompilerOptions] = macroRW

  implicit val compileScriptRW: RW[Compile.Script] = macroRW

  implicit val compileContractRW: RW[Compile.Contract] = macroRW

  implicit val compileProjectRW: RW[Compile.Project] = macroRW

  implicit val compilePatchRW: RW[CompileProjectResult.Patch] =
    readwriter[String].bimap(_.value, CompileProjectResult.Patch(_))
  implicit val compileResultFieldsRW: RW[CompileResult.FieldsSig]     = macroRW
  implicit val compileResultFunctionRW: RW[CompileResult.FunctionSig] = macroRW
  implicit val compileResultMapsRW: RW[CompileResult.MapsSig]         = macroRW
  implicit val compileResultEventRW: RW[CompileResult.EventSig]       = macroRW
  implicit val compileResultConstantRW: RW[CompileResult.Constant]    = macroRW
  implicit val compileResultEnumFieldRW: RW[CompileResult.EnumField]  = macroRW
  implicit val compileResultEnumRW: RW[CompileResult.Enum]            = macroRW
  implicit val compileResultStructRW: RW[CompileResult.StructSig]     = macroRW
  implicit val compileScriptResultRW: RW[CompileScriptResult]         = macroRW
  implicit val compileContractResultRW: RW[CompileContractResult]     = macroRW
  implicit val compileProjectResultRW: RW[CompileProjectResult]       = macroRW

  implicit val statefulContractReader: Reader[StatefulContract] = StringReader.map { input =>
    val bs =
      Hex.from(input).getOrElse(throw Abort(s"Invalid hex string for stateful contract $input"))
    deserialize[StatefulContract](bs) match {
      case Right(contract) => contract
      case Left(error)     => throw Abort(s"Invalid stateful contract $input: $error")
    }
  }
  implicit val statefulContractWriter: Writer[StatefulContract] =
    StringWriter.comap(contract => Hex.toHexString(serialize(contract)))

  implicit val assetRW: ReadWriter[AssetState]                      = macroRW
  implicit val existingContractRW: ReadWriter[ContractState]        = macroRW
  implicit val testContractInputAssetRW: ReadWriter[TestInputAsset] = macroRW
  implicit val testContractRW: ReadWriter[TestContract]             = macroRW
  implicit val debugMessageRW: ReadWriter[DebugMessage]             = macroRW
  implicit val testContractResultRW: ReadWriter[TestContractResult] = macroRW

  implicit val callContractRW: ReadWriter[CallContract]                   = macroRW
  implicit val callContractSucceededRW: ReadWriter[CallContractSucceeded] = macroRW
  implicit val callContractFailedRW: ReadWriter[CallContractFailed]       = macroRW
  implicit val callContractResultRW: ReadWriter[CallContractResult]       = macroRW

  implicit val callTxScriptRW: ReadWriter[CallTxScript]             = macroRW
  implicit val callTxScriptResultRW: ReadWriter[CallTxScriptResult] = macroRW

  implicit val multipleCallContractRW: ReadWriter[MultipleCallContract]             = macroRW
  implicit val multipleCallContractResultRW: ReadWriter[MultipleCallContractResult] = macroRW

  implicit val txResultRW: RW[SubmitTxResult] = macroRW

  implicit val getHashesAtHeightRW: RW[GetHashesAtHeight] = macroRW

  implicit val hashesAtHeightRW: RW[HashesAtHeight] = macroRW

  implicit val getChainInfoRW: RW[GetChainInfo] = macroRW

  implicit val chainInfoRW: RW[ChainInfo] = macroRW

  implicit val getBlockRW: RW[GetBlock] = macroRW

  implicit val minerActionRW: RW[MinerAction] = readwriter[String].bimap(
    {
      case MinerAction.StartMining => "start-mining"
      case MinerAction.StopMining  => "stop-mining"
    },
    {
      case "start-mining" => MinerAction.StartMining
      case "stop-mining"  => MinerAction.StopMining
      case other          => throw Abort(s"Invalid miner action: $other")
    }
  )

  implicit val misbehaviorActionUnBanRW: RW[MisbehaviorAction.Unban] = macroRW
  implicit val misbehaviorActionBanRW: RW[MisbehaviorAction.Ban]     = macroRW
  implicit val misbehaviorActionRW: RW[MisbehaviorAction]            = macroRW

  implicit val discoveryActionUnreachableRW: RW[DiscoveryAction.Unreachable] = macroRW

  implicit val discoveryActionReachableRW: RW[DiscoveryAction.Reachable] = macroRW

  implicit val discoveryActionRW: RW[DiscoveryAction] = macroRW

  implicit val minerAddressesRW: RW[MinerAddresses] = macroRW

  implicit val peerInfoRW: ReadWriter[model.BrokerInfo] = {
    readwriter[ujson.Value].bimap[model.BrokerInfo](
      peer =>
        ujson.Obj(
          "cliqueId"  -> writeJs(peer.cliqueId),
          "brokerId"  -> writeJs(peer.brokerId),
          "brokerNum" -> writeJs(peer.brokerNum),
          "address"   -> writeJs(peer.address)
        ),
      json =>
        model.BrokerInfo.unsafe(
          read[CliqueId](json("cliqueId")),
          read[Int](json("brokerId")),
          read[Int](json("brokerNum")),
          read[InetSocketAddress](json("address"))
        )
    )
  }

  implicit val interCliqueSyncedStatusRW: RW[InterCliquePeerInfo] = macroRW

  implicit val mnemonicSizeRW: RW[Mnemonic.Size] = readwriter[Int].bimap(
    _.value,
    { size =>
      Mnemonic
        .Size(size)
        .getOrElse(
          throw Abort(
            s"Invalid mnemonic size: $size, expected: ${Mnemonic.Size.list.map(_.value).mkString(", ")}"
          )
        )
    }
  )

  implicit val valBoolRW: RW[ValBool]       = macroRW
  implicit val valU256RW: RW[ValU256]       = macroRW
  implicit val valI256RW: RW[ValI256]       = macroRW
  implicit val valAddressRW: RW[ValAddress] = macroRW
  implicit val valByteVecRW: RW[ValByteVec] = macroRW
  implicit val valArrayRW: RW[ValArray]     = macroRW
  implicit val valRW: RW[Val] = RW.merge(
    valBoolRW,
    valU256RW,
    valI256RW,
    valAddressRW,
    valByteVecRW,
    valArrayRW
  )

  implicit val apiKeyEncoder: Writer[ApiKey] = StringWriter.comap(_.value)
  implicit val apiKeyDecoder: Reader[ApiKey] = StringReader.map { raw =>
    ApiKey.from(raw) match {
      case Right(apiKey) => apiKey
      case Left(error)   => throw Abort(error)
    }
  }

  implicit val verifySignatureRW: RW[VerifySignature] = macroRW

  implicit val targetToHashrateRW: RW[TargetToHashrate]              = macroRW
  implicit val targetToHashrateResultRW: RW[TargetToHashrate.Result] = macroRW

  implicit val releaseVersionEncoder: Writer[model.ReleaseVersion] = StringWriter.comap(_.toString)
  implicit val releaseVersionDecoder: Reader[model.ReleaseVersion] = StringReader.map { raw =>
    model.ReleaseVersion.from(raw) match {
      case Some(version) => version
      case None          => throw Abort(s"Cannot decode version: $raw")
    }
  }

  implicit val contractEventRW: RW[ContractEvent]                       = macroRW
  implicit val eventsRW: RW[ContractEvents]                             = macroRW
  implicit val contractEventByTxIdRW: RW[ContractEventByTxId]           = macroRW
  implicit val eventsByTxIdRW: RW[ContractEventsByTxId]                 = macroRW
  implicit val contractEventByBlockHashRW: RW[ContractEventByBlockHash] = macroRW
  implicit val eventsByBlockHashRW: RW[ContractEventsByBlockHash]       = macroRW
  implicit val contractParentRW: RW[ContractParent]                     = macroRW
  implicit val subContractsRW: RW[SubContracts]                         = macroRW

  implicit val addressAssetStateRW: RW[AddressAssetState] = macroRW
  implicit val simulationResultRW: RW[SimulationResult]   = macroRW

  implicit val buildGrouplessTransferResultRW: RW[BuildGrouplessTransferTxResult] = macroRW
  implicit val buildGrouplessExecuteScriptTxResultRW: RW[BuildGrouplessExecuteScriptTxResult] =
    macroRW
  implicit val buildGrouplessDeployContractTxResultRW: RW[BuildGrouplessDeployContractTxResult] =
    macroRW

  implicit val buildTransferResultRW: RW[BuildTransferTxResult]               = macroRW
  implicit val buildExecuteScriptTxResultRW: RW[BuildExecuteScriptTxResult]   = macroRW
  implicit val buildDeployContractTxResultRW: RW[BuildDeployContractTxResult] = macroRW
  private def bytesWriter[T <: RandomBytes]: Writer[T] =
    StringWriter.comap[T](_.toHexString)

  private def bytesReader[T <: RandomBytes](from: ByteString => Option[T]): Reader[T] =
    StringReader.map { input =>
      val keyOpt = for {
        bs  <- Hex.from(input)
        key <- from(bs)
      } yield key
      keyOpt.getOrElse(throw Abort(s"Unable to decode key from $input"))
    }
}
