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

import java.net.InetAddress

import com.typesafe.scalalogging.StrictLogging
import sttp.model.StatusCode
import sttp.tapir._
import sttp.tapir.EndpointIO.Example
import sttp.tapir.EndpointOutput.OneOfMapping
import sttp.tapir.generic.auto._

import org.alephium.api.TapirCodecs
import org.alephium.api.TapirSchemasLike
import org.alephium.api.UtilJson.{avectorReadWriter, inetAddressRW}
import org.alephium.api.model._
import org.alephium.json.Json.ReadWriter
import org.alephium.protocol.{ALPH, BlockHash, Hash}
import org.alephium.protocol.config.GroupConfig
import org.alephium.protocol.model._
import org.alephium.util.{AVector, TimeStamp}

trait Endpoints
    extends ApiModelCodec
    with BaseEndpoint
    with EndpointsExamples
    with TapirCodecs
    with TapirSchemasLike
    with StrictLogging {
  import Endpoints._

  implicit def groupConfig: GroupConfig

  private val timeIntervalQuery: EndpointInput[TimeInterval] =
    query[TimeStamp]("fromTs")
      .and(query[Option[TimeStamp]]("toTs"))
      .map { case (from, to) => TimeInterval(from, to) }(timeInterval =>
        (timeInterval.from, timeInterval.toOpt)
      )
      .validate(TimeInterval.validator)

  private lazy val chainIndexQuery: EndpointInput[ChainIndex] =
    query[GroupIndex]("fromGroup")
      .and(query[GroupIndex]("toGroup"))
      .map { case (from, to) => ChainIndex(from, to) }(chainIndex =>
        (chainIndex.from, chainIndex.to)
      )

  private val infosEndpoint: BaseEndpoint[Unit, Unit] =
    baseEndpoint
      .in("infos")
      .tag("Infos")

  private val addressesEndpoint: BaseEndpoint[Unit, Unit] =
    baseEndpoint
      .in("addresses")
      .tag("Addresses")

  private val multisigEndpoint: BaseEndpoint[Unit, Unit] =
    baseEndpoint
      .in("multisig")
      .tag("Multi-signature")

  private val transactionsEndpoint: BaseEndpoint[Unit, Unit] =
    baseEndpoint
      .in("transactions")
      .tag("Transactions")

  private val minersEndpoint: BaseEndpoint[Unit, Unit] =
    baseEndpoint
      .in("miners")
      .tag("Miners")

  private val contractsEndpoint: BaseEndpoint[Unit, Unit] =
    baseEndpoint
      .in("contracts")
      .tag("Contracts")

  private val contractsUnsignedTxEndpoint: BaseEndpoint[Unit, Unit] =
    contractsEndpoint.in("unsigned-tx")

  private val blockflowEndpoint: BaseEndpoint[Unit, Unit] =
    baseEndpoint
      .in("blockflow")
      .tag("Blockflow")

  private val utilsEndpoint: BaseEndpoint[Unit, Unit] =
    baseEndpoint
      .in("utils")
      .tag("Utils")

  private val eventsEndpoint: BaseEndpoint[Unit, Unit] =
    baseEndpoint
      .in("events")
      .tag("Events")

  private val contractEventsEndpoint: BaseEndpoint[Unit, Unit] =
    eventsEndpoint
      .in("contract")

  private val txScriptEventsEndpoint: BaseEndpoint[Unit, Unit] =
    eventsEndpoint
      .in("tx-script")

  val getNodeInfo: BaseEndpoint[Unit, NodeInfo] =
    infosEndpoint.get
      .in("node")
      .out(jsonBody[NodeInfo])
      .summary("Get info about that node")

  val getNodeVersion: BaseEndpoint[Unit, NodeVersion] =
    infosEndpoint.get
      .in("version")
      .out(jsonBody[NodeVersion])
      .summary("Get version about that node")

  val getChainParams: BaseEndpoint[Unit, ChainParams] =
    infosEndpoint.get
      .in("chain-params")
      .out(jsonBody[ChainParams])
      .summary("Get key params about your blockchain")

  val getSelfClique: BaseEndpoint[Unit, SelfClique] =
    infosEndpoint.get
      .in("self-clique")
      .out(jsonBody[SelfClique])
      .summary("Get info about your own clique")

  val getSelfCliqueSynced: BaseEndpoint[Unit, Boolean] =
    infosEndpoint.get
      .in("self-clique-synced")
      .out(jsonBody[Boolean])
      .summary("Is your clique synced?")

  val getInterCliquePeerInfo: BaseEndpoint[Unit, AVector[InterCliquePeerInfo]] =
    infosEndpoint.get
      .in("inter-clique-peer-info")
      .out(jsonBody[AVector[InterCliquePeerInfo]])
      .summary("Get infos about the inter cliques")

  val getDiscoveredNeighbors: BaseEndpoint[Unit, AVector[BrokerInfo]] =
    infosEndpoint.get
      .in("discovered-neighbors")
      .out(jsonBody[AVector[BrokerInfo]])
      .summary("Get discovered neighbors")

  val getMisbehaviors: BaseEndpoint[Unit, AVector[PeerMisbehavior]] =
    infosEndpoint.get
      .in("misbehaviors")
      .out(jsonBody[AVector[PeerMisbehavior]])
      .summary("Get the misbehaviors of peers")

  val misbehaviorAction: BaseEndpoint[MisbehaviorAction, Unit] =
    infosEndpoint.post
      .in("misbehaviors")
      .in(jsonBody[MisbehaviorAction])
      .summary("Ban/Unban given peers")

  val getUnreachableBrokers: BaseEndpoint[Unit, AVector[InetAddress]] =
    infosEndpoint.get
      .in("unreachable")
      .out(jsonBody[AVector[InetAddress]])
      .summary("Get the unreachable brokers")

  val discoveryAction: BaseEndpoint[DiscoveryAction, Unit] =
    infosEndpoint.post
      .in("discovery")
      .in(jsonBody[DiscoveryAction])
      .summary("Set brokers to be unreachable/reachable")

  val getHistoryHashRate: BaseEndpoint[TimeInterval, HashRateResponse] =
    infosEndpoint.get
      .in("history-hashrate")
      .in(timeIntervalQuery)
      .out(jsonBody[HashRateResponse])
      .summary("Get history average hashrate on the given time interval")

  val getCurrentHashRate: BaseEndpoint[Option[TimeSpan], HashRateResponse] =
    infosEndpoint.get
      .in("current-hashrate")
      .in(query[Option[TimeSpan]]("timespan"))
      .out(jsonBody[HashRateResponse])
      .summary("Get average hashrate from `now - timespan(millis)` to `now`")

  val getBlockflow: BaseEndpoint[TimeInterval, FetchResponse] =
    blockflowEndpoint.get
      .in(timeIntervalQuery)
      .out(jsonBody[FetchResponse])
      .summary("List blocks on the given time interval")

  val getBlock: BaseEndpoint[BlockHash, BlockEntry] =
    blockflowEndpoint.get
      .in("blocks")
      .in(path[BlockHash]("block_hash"))
      .out(jsonBody[BlockEntry])
      .summary("Get a block with hash")

  val isBlockInMainChain: BaseEndpoint[BlockHash, Boolean] =
    blockflowEndpoint.get
      .in("is-block-in-main-chain")
      .in(query[BlockHash]("blockHash"))
      .out(jsonBody[Boolean])
      .summary("Check if the block is in main chain")

  val getBalance: BaseEndpoint[(Address.Asset, Option[Int]), Balance] =
    addressesEndpoint.get
      .in(path[Address.Asset]("address"))
      .in("balance")
      .in(query[Option[Int]]("utxosLimit"))
      .out(jsonBodyWithAlph[Balance])
      .summary("Get the balance of an address")

  // TODO: query based on token id?
  val getUTXOs: BaseEndpoint[(Address.Asset, Option[Int]), UTXOs] =
    addressesEndpoint.get
      .in(path[Address.Asset]("address"))
      .in("utxos")
      .in(query[Option[Int]]("utxosLimit"))
      .out(jsonBody[UTXOs])
      .summary("Get the UTXOs of an address")

  val getGroup: BaseEndpoint[Address, Group] =
    addressesEndpoint.get
      .in(path[Address]("address"))
      .in("group")
      .out(jsonBody[Group])
      .summary("Get the group of an address")

  val getGroupLocal: BaseEndpoint[Address, Group] =
    addressesEndpoint.get
      .in(path[Address]("address"))
      .in("groupLocal")
      .out(jsonBody[Group])
      .summary("Get the group of an address. Checks locally for contract addressses")
  //have to be lazy to let `groupConfig` being initialized
  lazy val getHashesAtHeight: BaseEndpoint[(ChainIndex, Int), HashesAtHeight] =
    blockflowEndpoint.get
      .in("hashes")
      .in(chainIndexQuery)
      .in(query[Int]("height"))
      .out(jsonBody[HashesAtHeight])
      .summary("Get all block's hashes at given height for given groups")

  //have to be lazy to let `groupConfig` being initialized
  lazy val getChainInfo: BaseEndpoint[ChainIndex, ChainInfo] =
    blockflowEndpoint.get
      .in("chain-info")
      .in(chainIndexQuery)
      .out(jsonBody[ChainInfo])
      .summary("Get infos about the chain from the given groups")

  //have to be lazy to let `groupConfig` being initialized
  lazy val listUnconfirmedTransactions: BaseEndpoint[Unit, AVector[UnconfirmedTransactions]] =
    transactionsEndpoint.get
      .in("unconfirmed")
      .out(jsonBody[AVector[UnconfirmedTransactions]])
      .summary("List unconfirmed transactions")

  val buildTransaction: BaseEndpoint[BuildTransaction, BuildTransactionResult] =
    transactionsEndpoint.post
      .in("build")
      .in(jsonBodyWithAlph[BuildTransaction])
      .out(jsonBody[BuildTransactionResult])
      .summary("Build an unsigned transaction to a number of recipients")

  val buildSweepAddressTransactions
      : BaseEndpoint[BuildSweepAddressTransactions, BuildSweepAddressTransactionsResult] =
    transactionsEndpoint.post
      .in("sweep-address")
      .in("build")
      .in(jsonBody[BuildSweepAddressTransactions])
      .out(jsonBody[BuildSweepAddressTransactionsResult])
      .summary(
        "Build unsigned transactions to send all unlocked balanced of one address to another address"
      )

  val submitTransaction: BaseEndpoint[SubmitTransaction, TxResult] =
    transactionsEndpoint.post
      .in("submit")
      .in(jsonBody[SubmitTransaction])
      .out(jsonBody[TxResult])
      .summary("Submit a signed transaction")

  val buildMultisigAddress: BaseEndpoint[BuildMultisigAddress, BuildMultisigAddressResult] =
    multisigEndpoint.post
      .in("address")
      .in(jsonBodyWithAlph[BuildMultisigAddress])
      .out(jsonBody[BuildMultisigAddressResult])
      .summary("Create the multisig address and unlock script")

  val buildMultisig: BaseEndpoint[BuildMultisig, BuildTransactionResult] =
    multisigEndpoint.post
      .in("build")
      .in(jsonBody[BuildMultisig])
      .out(jsonBody[BuildTransactionResult])
      .summary("Build a multisig unsigned transaction")

  val submitMultisigTransaction: BaseEndpoint[SubmitMultisig, TxResult] =
    multisigEndpoint.post
      .in("submit")
      .in(jsonBody[SubmitMultisig])
      .out(jsonBody[TxResult])
      .summary("Submit a multi-signed transaction")

  lazy val getTransactionStatus
      : BaseEndpoint[(Hash, Option[GroupIndex], Option[GroupIndex]), TxStatus] =
    transactionsEndpoint.get
      .in("status")
      .in(query[Hash]("txId"))
      .in(query[Option[GroupIndex]]("fromGroup"))
      .in(query[Option[GroupIndex]]("toGroup"))
      .out(jsonBody[TxStatus])
      .summary("Get tx status")

  val decodeUnsignedTransaction: BaseEndpoint[DecodeTransaction, UnsignedTx] =
    transactionsEndpoint.post
      .in("decode-unsigned-tx")
      .in(jsonBody[DecodeTransaction])
      .out(jsonBody[UnsignedTx])
      .summary("Decode an unsigned transaction")

  val minerAction: BaseEndpoint[MinerAction, Boolean] =
    minersEndpoint.post
      .in("cpu-mining")
      .in(query[MinerAction]("action").examples(minerActionExamples))
      .out(jsonBody[Boolean])
      .summary("Execute an action on CPU miner. !!! for test only !!!")

  val minerListAddresses: BaseEndpoint[Unit, MinerAddresses] =
    minersEndpoint.get
      .in("addresses")
      .out(jsonBody[MinerAddresses])
      .summary("List miner's addresses")

  val minerUpdateAddresses: BaseEndpoint[MinerAddresses, Unit] =
    minersEndpoint.put
      .in("addresses")
      .in(jsonBody[MinerAddresses])
      .summary("Update miner's addresses, but better to use user.conf instead")

  val compileScript: BaseEndpoint[Compile.Script, CompileResult] =
    contractsEndpoint.post
      .in("compile-script")
      .in(jsonBody[Compile.Script])
      .out(jsonBody[CompileResult])
      .summary("Compile a script")

  val buildScript: BaseEndpoint[BuildScriptTx, BuildScriptTxResult] =
    contractsUnsignedTxEndpoint.post
      .in("build-script")
      .in(jsonBody[BuildScriptTx])
      .out(jsonBody[BuildScriptTxResult])
      .summary("Build an unsigned script")

  val compileContract: BaseEndpoint[Compile.Contract, CompileResult] =
    contractsEndpoint.post
      .in("compile-contract")
      .in(jsonBody[Compile.Contract])
      .out(jsonBody[CompileResult])
      .summary("Compile a smart contract")

  val buildContract: BaseEndpoint[BuildContractDeployScriptTx, BuildContractDeployScriptTxResult] =
    contractsUnsignedTxEndpoint.post
      .in("build-contract")
      .in(jsonBody[BuildContractDeployScriptTx])
      .out(jsonBody[BuildContractDeployScriptTxResult])
      .summary("Build an unsigned contract")

  lazy val contractState: BaseEndpoint[(Address.Contract, GroupIndex), ContractState] =
    contractsEndpoint.get
      .in(path[Address.Contract]("address"))
      .in("state")
      .in(query[GroupIndex]("group"))
      .out(jsonBody[ContractState])
      .summary("Get contract state")

  lazy val testContract: BaseEndpoint[TestContract, TestContractResult] =
    contractsEndpoint.post
      .in("test-contract")
      .in(jsonBody[TestContract])
      .out(jsonBody[TestContractResult])
      .summary("Test contract")

  val exportBlocks: BaseEndpoint[ExportFile, Unit] =
    baseEndpoint.post
      .in("export-blocks")
      .in(jsonBody[ExportFile])
      .summary("Exports all the blocks")

  val metrics: BaseEndpointWithoutApi[Unit, String] =
    baseEndpointWithoutApiKey.get
      .in("metrics")
      .out(alphPlainTextBody)
      .summary("Exports all prometheus metrics")

  val getBlockHeaderEntry: BaseEndpoint[BlockHash, BlockHeaderEntry] =
    blockflowEndpoint.get
      .in("headers")
      .in(path[BlockHash]("block_hash"))
      .out(jsonBody[BlockHeaderEntry])
      .summary("Get block header")

  val verifySignature: BaseEndpoint[VerifySignature, Boolean] =
    utilsEndpoint.post
      .in("verify-signature")
      .in(jsonBody[VerifySignature])
      .out(jsonBody[Boolean])
      .summary("Verify the SecP256K1 signature of some data")

  val checkHashIndexing: BaseEndpoint[Unit, Unit] =
    utilsEndpoint.put
      .in("check-hash-indexing")
      .summary("Check and repair the indexing of block hashes")

  val getContractEventsForBlock: BaseEndpoint[(BlockHash, Address.Contract), Events] =
    contractEventsEndpoint.get
      .in("in-block")
      .in(query[BlockHash]("block"))
      .in(query[Address.Contract]("contractAddress"))
      .out(jsonBody[Events])
      .summary("Get events for a contract within a block")

  val getContractEventsWithinBlocks
      : BaseEndpoint[(BlockHash, Option[BlockHash], Address.Contract), AVector[Events]] =
    contractEventsEndpoint.get
      .in("within-blocks")
      .in(query[BlockHash]("fromBlock"))
      .in(query[Option[BlockHash]]("toBlock"))
      .in(query[Address.Contract]("contractAddress"))
      .out(jsonBody[AVector[Events]])
      .summary("Get events for a contract within a range of blocks")

  val getContractEventsWithinTimeInterval
      : BaseEndpoint[(TimeInterval, Address.Contract), AVector[Events]] =
    contractEventsEndpoint.get
      .in("within-time-interval")
      .in(timeIntervalQuery)
      .in(query[Address.Contract]("contractAddress"))
      .out(jsonBody[AVector[Events]])
      .summary("Get events for a contract within a time interval")

  val getTxScriptEvents: BaseEndpoint[(BlockHash, Hash), Events] =
    txScriptEventsEndpoint.get
      .in(query[BlockHash]("block"))
      .in(query[Hash]("txId"))
      .out(jsonBody[Events])
      .summary("Get events for a TxScript")
}

object Endpoints {
  // scalastyle:off regex
  def error[S <: StatusCode, T <: ApiError[S]: ReadWriter: Schema](
      apiError: ApiError.Companion[S, T],
      matcher: PartialFunction[Any, Boolean]
  )(implicit
      examples: List[Example[T]]
  ): OneOfMapping[T] = {
    oneOfMappingValueMatcher(apiError.statusCode, jsonBody[T].description(apiError.description))(
      matcher
    )
  }
  // scalastyle:on regex

  def jsonBody[T: ReadWriter: Schema](implicit
      examples: List[Example[T]]
  ): EndpointIO.Body[String, T] = {
    alphJsonBody[T].examples(examples)
  }

  def jsonBodyWithAlph[T: ReadWriter: Schema](implicit
      examples: List[Example[T]]
  ): EndpointIO.Body[String, T] = {
    alphJsonBody[T]
      .examples(examples)
      .description(
        s"""Format 1: `${ALPH.oneAlph}`\n\nFormat 2: `x.y ALPH`, where `1 ALPH = ${ALPH.oneAlph}`"""
      )
  }
}
