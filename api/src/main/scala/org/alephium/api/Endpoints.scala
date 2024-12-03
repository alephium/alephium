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
import sttp.tapir.EndpointOutput.OneOfVariant
import sttp.tapir.generic.auto._

import org.alephium.api.TapirCodecs
import org.alephium.api.TapirSchemasLike
import org.alephium.api.UtilJson.{avectorReadWriter, inetAddressRW}
import org.alephium.api.model._
import org.alephium.json.Json.ReadWriter
import org.alephium.protocol.{ALPH, Hash}
import org.alephium.protocol.config.GroupConfig
import org.alephium.protocol.model.{Transaction => _, _}
import org.alephium.protocol.vm.StatefulContract
import org.alephium.util.{AVector, TimeStamp}

//scalastyle:off file.size.limit
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

  private val counterQuery: EndpointInput[CounterRange] =
    query[Int]("start")
      .and(query[Option[Int]]("limit"))
      .map { case (start, limitOpt) => CounterRange(start, limitOpt) }(counterQuery =>
        (counterQuery.start, counterQuery.limitOpt)
      )
      .validate(CounterRange.validator)

  private lazy val chainIndexQuery: EndpointInput[ChainIndex] =
    query[GroupIndex]("fromGroup")
      .and(query[GroupIndex]("toGroup"))
      .map { case (from, to) => ChainIndex(from, to) }(chainIndex =>
        (chainIndex.from, chainIndex.to)
      )

  private val outputRefQuery: EndpointInput[OutputRef] =
    query[Int]("hint")
      .and(query[Hash]("key"))
      .map { case (hint, key) => OutputRef(hint, key) }(outputRef =>
        (outputRef.hint, outputRef.key)
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

  private lazy val mempoolEndpoint: BaseEndpoint[Unit, Unit] =
    baseEndpoint
      .in("mempool")
      .tag("Mempool")

  private lazy val mempoolTxEndpoint: BaseEndpoint[Unit, Unit] =
    mempoolEndpoint
      .in("transactions")

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

  val getCurrentDifficulty: BaseEndpoint[Unit, CurrentDifficulty] =
    infosEndpoint.get
      .in("current-difficulty")
      .out(jsonBody[CurrentDifficulty])
      .summary("Get the average difficulty of the latest blocks from all shards")

  val getBlocks: BaseEndpoint[TimeInterval, BlocksPerTimeStampRange] =
    blockflowEndpoint.get
      .in("blocks")
      .in(timeIntervalQuery)
      .out(jsonBody[BlocksPerTimeStampRange])
      .summary("List blocks on the given time interval")

  val getBlocksAndEvents: BaseEndpoint[TimeInterval, BlocksAndEventsPerTimeStampRange] =
    blockflowEndpoint.get
      .in("blocks-with-events")
      .in(timeIntervalQuery)
      .out(jsonBody[BlocksAndEventsPerTimeStampRange])
      .summary("List blocks with events on the given time interval")

  val getBlock: BaseEndpoint[BlockHash, BlockEntry] =
    blockflowEndpoint.get
      .in("blocks")
      .in(path[BlockHash]("block_hash"))
      .out(jsonBody[BlockEntry])
      .summary("Get a block with hash")

  lazy val getRichBlocksAndEvents
      : BaseEndpoint[TimeInterval, RichBlocksAndEventsPerTimeStampRange] =
    blockflowEndpoint.get
      .in("rich-blocks")
      .in(timeIntervalQuery)
      .out(jsonBody[RichBlocksAndEventsPerTimeStampRange])
      .summary(
        "Given a time interval, list blocks containing events and transactions with enriched input information when node indexes are enabled."
      )

  lazy val getRichBlockAndEvents: BaseEndpoint[BlockHash, RichBlockAndEvents] =
    blockflowEndpoint.get
      .in("rich-blocks")
      .in(path[BlockHash]("block_hash"))
      .out(jsonBody[RichBlockAndEvents])
      .summary(
        "Get a block containing events and transactions with enriched input information when node indexes are enabled."
      )

  lazy val getMainChainBlockByGhostUncle: BaseEndpoint[BlockHash, BlockEntry] =
    blockflowEndpoint.get
      .in("main-chain-block-by-ghost-uncle")
      .in(path[BlockHash]("ghost_uncle_hash"))
      .out(jsonBody[BlockEntry])
      .summary("Get a mainchain block by ghost uncle hash")

  val getBlockAndEvents: BaseEndpoint[BlockHash, BlockAndEvents] =
    blockflowEndpoint.get
      .in("blocks-with-events")
      .in(path[BlockHash]("block_hash"))
      .out(jsonBody[BlockAndEvents])
      .summary("Get a block and events with hash")

  val isBlockInMainChain: BaseEndpoint[BlockHash, Boolean] =
    blockflowEndpoint.get
      .in("is-block-in-main-chain")
      .in(query[BlockHash]("blockHash"))
      .out(jsonBody[Boolean])
      .summary("Check if the block is in main chain")

  val getBalance: BaseEndpoint[(Address, Option[Boolean]), Balance] =
    addressesEndpoint.get
      .in(path[Address]("address"))
      .in("balance")
      .in(query[Option[Boolean]]("mempool"))
      .out(jsonBodyWithAlph[Balance])
      .summary("Get the balance of an address")

  // TODO: query based on token id?
  val getUTXOs: BaseEndpoint[Address, UTXOs] =
    addressesEndpoint.get
      .in(path[Address]("address"))
      .in("utxos")
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
  // have to be lazy to let `groupConfig` being initialized
  lazy val getHashesAtHeight: BaseEndpoint[(ChainIndex, Int), HashesAtHeight] =
    blockflowEndpoint.get
      .in("hashes")
      .in(chainIndexQuery)
      .in(query[Int]("height"))
      .out(jsonBody[HashesAtHeight])
      .summary("Get all block's hashes at given height for given groups")

  // have to be lazy to let `groupConfig` being initialized
  lazy val getChainInfo: BaseEndpoint[ChainIndex, ChainInfo] =
    blockflowEndpoint.get
      .in("chain-info")
      .in(chainIndexQuery)
      .out(jsonBody[ChainInfo])
      .summary("Get infos about the chain from the given groups")

  val buildTransferTransaction: BaseEndpoint[BuildTransferTx, BuildTransferTxResult] =
    transactionsEndpoint.post
      .in("build")
      .in(jsonBodyWithAlph[BuildTransferTx])
      .out(jsonBody[BuildTransferTxResult])
      .summary("Build an unsigned transfer transaction to a number of recipients")

  val buildTransferFromOneToManyGroups
      : BaseEndpoint[BuildTransferTx, AVector[BuildTransferTxResult]] =
    transactionsEndpoint.post
      .in("build-transfer-from-one-to-many-groups")
      .in(jsonBodyWithAlph[BuildTransferTx])
      .out(jsonBody[AVector[BuildTransferTxResult]])
      .summary(
        "Build unsigned transfer transactions from an address of one group to addresses of many groups. " +
          "Each target group requires a dedicated transaction or more in case large number of outputs needed to be split."
      )

  val buildMultiAddressesTransaction
      : BaseEndpoint[BuildMultiAddressesTransaction, BuildTransferTxResult] =
    transactionsEndpoint.post
      .in("build-multi-addresses")
      .in(jsonBodyWithAlph[BuildMultiAddressesTransaction])
      .out(jsonBody[BuildTransferTxResult])
      .summary(
        "Build an unsigned transaction with multiple addresses to a number of recipients"
      )

  val buildSweepAddressTransactions
      : BaseEndpoint[BuildSweepAddressTransactions, BuildSweepAddressTransactionsResult] =
    transactionsEndpoint.post
      .in("sweep-address")
      .in("build")
      .in(jsonBody[BuildSweepAddressTransactions])
      .out(jsonBody[BuildSweepAddressTransactionsResult])
      .summary(
        "Build unsigned transactions to send all unlocked ALPH and token balances of one address to another address"
      )

  val submitTransaction: BaseEndpoint[SubmitTransaction, SubmitTxResult] =
    transactionsEndpoint.post
      .in("submit")
      .in(jsonBody[SubmitTransaction])
      .out(jsonBody[SubmitTxResult])
      .summary("Submit a signed transaction")

  lazy val listMempoolTransactions: BaseEndpoint[Unit, AVector[MempoolTransactions]] =
    mempoolTxEndpoint.get
      .out(jsonBody[AVector[MempoolTransactions]])
      .summary("List mempool transactions")

  lazy val rebroadcastMempoolTransaction: BaseEndpoint[TransactionId, Unit] =
    mempoolTxEndpoint.put
      .in("rebroadcast")
      .in(query[TransactionId]("txId"))
      .summary("Rebroadcase a mempool transaction to the network")

  lazy val clearMempool: BaseEndpoint[Unit, Unit] =
    mempoolTxEndpoint.delete
      .summary("Remove all transactions from mempool")

  lazy val validateMempoolTransactions: BaseEndpoint[Unit, Unit] =
    mempoolTxEndpoint.put
      .in("validate")
      .summary("Validate all mempool transactions and remove invalid ones")

  val buildMultisigAddress: BaseEndpoint[BuildMultisigAddress, BuildMultisigAddressResult] =
    multisigEndpoint.post
      .in("address")
      .in(jsonBodyWithAlph[BuildMultisigAddress])
      .out(jsonBody[BuildMultisigAddressResult])
      .summary("Create the multisig address and unlock script")

  val buildMultisig: BaseEndpoint[BuildMultisig, BuildTransferTxResult] =
    multisigEndpoint.post
      .in("build")
      .in(jsonBody[BuildMultisig])
      .out(jsonBody[BuildTransferTxResult])
      .summary("Build a multisig unsigned transaction")

  val buildSweepMultisig: BaseEndpoint[BuildSweepMultisig, BuildSweepAddressTransactionsResult] =
    multisigEndpoint.post
      .in("sweep")
      .in(jsonBody[BuildSweepMultisig])
      .out(jsonBody[BuildSweepAddressTransactionsResult])
      .summary(
        "Sweep all unlocked ALPH and token balances of a multisig address to another address"
      )

  val submitMultisigTransaction: BaseEndpoint[SubmitMultisig, SubmitTxResult] =
    multisigEndpoint.post
      .in("submit")
      .in(jsonBody[SubmitMultisig])
      .out(jsonBody[SubmitTxResult])
      .summary("Submit a multi-signed transaction")

  lazy val getTransactionStatus
      : BaseEndpoint[(TransactionId, Option[GroupIndex], Option[GroupIndex]), TxStatus] =
    transactionsEndpoint.get
      .in("status")
      .in(query[TransactionId]("txId"))
      .in(query[Option[GroupIndex]]("fromGroup"))
      .in(query[Option[GroupIndex]]("toGroup"))
      .out(jsonBody[TxStatus])
      .summary("Get tx status")

  lazy val getTransactionStatusLocal
      : BaseEndpoint[(TransactionId, Option[GroupIndex], Option[GroupIndex]), TxStatus] =
    transactionsEndpoint.get
      .in("local-status")
      .in(query[TransactionId]("txId"))
      .in(query[Option[GroupIndex]]("fromGroup"))
      .in(query[Option[GroupIndex]]("toGroup"))
      .out(jsonBody[TxStatus])
      .summary("Get tx status, only from the local broker")

  lazy val getTxIdFromOutputRef: BaseEndpoint[OutputRef, TransactionId] =
    transactionsEndpoint.get
      .in("tx-id-from-outputref")
      .in(outputRefQuery)
      .out(jsonBody[TransactionId])
      .summary("Get transaction id from transaction output ref")

  val decodeUnsignedTransaction: BaseEndpoint[DecodeUnsignedTx, DecodeUnsignedTxResult] =
    transactionsEndpoint.post
      .in("decode-unsigned-tx")
      .in(jsonBody[DecodeUnsignedTx])
      .out(jsonBody[DecodeUnsignedTxResult])
      .summary("Decode an unsigned transaction")

  lazy val getTransaction
      : BaseEndpoint[(TransactionId, Option[GroupIndex], Option[GroupIndex]), Transaction] =
    transactionsEndpoint.get
      .in("details")
      .in(path[TransactionId]("txId"))
      .in(query[Option[GroupIndex]]("fromGroup"))
      .in(query[Option[GroupIndex]]("toGroup"))
      .out(jsonBody[Transaction])
      .summary("Get transaction details")

  lazy val getRichTransaction
      : BaseEndpoint[(TransactionId, Option[GroupIndex], Option[GroupIndex]), RichTransaction] =
    transactionsEndpoint.get
      .in("rich-details")
      .in(path[TransactionId]("txId"))
      .in(query[Option[GroupIndex]]("fromGroup"))
      .in(query[Option[GroupIndex]]("toGroup"))
      .out(jsonBody[RichTransaction])
      .summary("Get transaction with enriched input information when node indexes are enabled.")

  lazy val getRawTransaction
      : BaseEndpoint[(TransactionId, Option[GroupIndex], Option[GroupIndex]), RawTransaction] =
    transactionsEndpoint.get
      .in("raw")
      .in(path[TransactionId]("txId"))
      .in(query[Option[GroupIndex]]("fromGroup"))
      .in(query[Option[GroupIndex]]("toGroup"))
      .out(jsonBody[RawTransaction])
      .summary("Get raw transaction in hex format")

  val minerAction: BaseEndpoint[MinerAction, Boolean] =
    minersEndpoint.post
      .in("cpu-mining")
      .in(query[MinerAction]("action").examples(minerActionExamples))
      .out(jsonBody[Boolean])
      .summary("Execute an action on CPU miner. !!! for test only !!!")

  lazy val mineOneBlock: BaseEndpoint[ChainIndex, Boolean] =
    minersEndpoint.post
      .in("cpu-mining")
      .in("mine-one-block")
      .in(chainIndexQuery)
      .out(jsonBody[Boolean])
      .summary("Mine a block on CPU miner. !!! for test only !!!")

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

  val compileScript: BaseEndpoint[Compile.Script, CompileScriptResult] =
    contractsEndpoint.post
      .in("compile-script")
      .in(jsonBody[Compile.Script])
      .out(jsonBody[CompileScriptResult])
      .summary("Compile a script")

  val buildExecuteScriptTx: BaseEndpoint[BuildExecuteScriptTx, BuildExecuteScriptTxResult] =
    contractsUnsignedTxEndpoint.post
      .in("execute-script")
      .in(jsonBody[BuildExecuteScriptTx])
      .out(jsonBody[BuildExecuteScriptTxResult])
      .summary("Build an unsigned script")

  val compileContract: BaseEndpoint[Compile.Contract, CompileContractResult] =
    contractsEndpoint.post
      .in("compile-contract")
      .in(jsonBody[Compile.Contract])
      .out(jsonBody[CompileContractResult])
      .summary("Compile a smart contract")

  val compileProject: BaseEndpoint[Compile.Project, CompileProjectResult] =
    contractsEndpoint.post
      .in("compile-project")
      .in(jsonBody[Compile.Project])
      .out(jsonBody[CompileProjectResult])
      .summary("Compile a project")

  val buildDeployContractTx: BaseEndpoint[BuildDeployContractTx, BuildDeployContractTxResult] =
    contractsUnsignedTxEndpoint.post
      .in("deploy-contract")
      .in(jsonBody[BuildDeployContractTx])
      .out(jsonBody[BuildDeployContractTxResult])
      .summary("Build an unsigned contract")

  val buildChainedTransactions
      : BaseEndpoint[AVector[BuildChainedTx], AVector[BuildChainedTxResult]] =
    transactionsEndpoint.post
      .in("build-chained")
      .in(jsonBody[AVector[BuildChainedTx]])
      .out(jsonBody[AVector[BuildChainedTxResult]])
      .summary("Build a chain of transactions")

  lazy val contractState: BaseEndpoint[Address.Contract, ContractState] =
    contractsEndpoint.get
      .in(path[Address.Contract]("address"))
      .in("state")
      .out(jsonBody[ContractState])
      .summary("Get contract state")

  lazy val contractCode: BaseEndpoint[Hash, StatefulContract] =
    contractsEndpoint.get
      .in(path[Hash]("codeHash"))
      .in("code")
      .out(jsonBody[StatefulContract])
      .summary("Get contract code by code hash")

  lazy val testContract: BaseEndpoint[TestContract, TestContractResult] =
    contractsEndpoint.post
      .in("test-contract")
      .in(jsonBody[TestContract])
      .out(jsonBody[TestContractResult])
      .summary("Test contract")

  lazy val callContract: BaseEndpoint[CallContract, CallContractResult] =
    contractsEndpoint.post
      .in("call-contract")
      .in(jsonBody[CallContract])
      .out(jsonBody[CallContractResult])
      .summary("Call contract")

  lazy val multiCallContract: BaseEndpoint[MultipleCallContract, MultipleCallContractResult] =
    contractsEndpoint.post
      .in("multicall-contract")
      .in(jsonBody[MultipleCallContract])
      .out(jsonBody[MultipleCallContractResult])
      .summary("Multiple call contract")

  lazy val parentContract: BaseEndpoint[Address.Contract, ContractParent] =
    contractsEndpoint.get
      .in(path[Address.Contract]("address"))
      .in("parent")
      .out(jsonBody[ContractParent])
      .summary("Get parent contract address")

  lazy val subContracts: BaseEndpoint[(Address.Contract, CounterRange), SubContracts] =
    contractsEndpoint.get
      .in(path[Address.Contract]("address"))
      .in("sub-contracts")
      .in(counterQuery)
      .out(jsonBody[SubContracts])
      .summary("Get sub-contract addresses")

  val subContractsCurrentCount: BaseEndpoint[Address.Contract, Int] =
    contractsEndpoint.get
      .in(path[Address.Contract]("address"))
      .in("sub-contracts")
      .in("current-count")
      .out(jsonBody[Int])
      .summary("Get current value of the sub-contracts counter for a contract")

  lazy val callTxScript: BaseEndpoint[CallTxScript, CallTxScriptResult] =
    contractsEndpoint.post
      .in("call-tx-script")
      .in(jsonBody[CallTxScript])
      .out(jsonBody[CallTxScriptResult])
      .summary("Call TxScript")

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

  val getRawBlock: BaseEndpoint[BlockHash, RawBlock] =
    blockflowEndpoint.get
      .in("raw-blocks")
      .in(path[BlockHash]("block_hash"))
      .out(jsonBody[RawBlock])
      .summary("Get raw block in hex format")

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

  val targetToHashrate: BaseEndpoint[TargetToHashrate, TargetToHashrate.Result] =
    utilsEndpoint.post
      .in("target-to-hashrate")
      .in(jsonBody[TargetToHashrate])
      .out(jsonBody[TargetToHashrate.Result])
      .summary("Convert a target to hashrate")

  lazy val getContractEvents
      : BaseEndpoint[(Address.Contract, CounterRange, Option[GroupIndex]), ContractEvents] =
    contractEventsEndpoint.get
      .in(path[Address.Contract]("contractAddress"))
      .in(counterQuery)
      .in(query[Option[GroupIndex]]("group"))
      .out(jsonBody[ContractEvents])
      .summary("Get events for a contract within a counter range")

  val getContractEventsCurrentCount: BaseEndpoint[Address.Contract, Int] =
    contractEventsEndpoint.get
      .in(path[Address.Contract]("contractAddress"))
      .in("current-count")
      .out(jsonBody[Int])
      .summary("Get current value of the events counter for a contract")

  lazy val getEventsByTxId
      : BaseEndpoint[(TransactionId, Option[GroupIndex]), ContractEventsByTxId] =
    eventsEndpoint
      .in("tx-id")
      .get
      .in(path[TransactionId]("txId"))
      .in(query[Option[GroupIndex]]("group"))
      .out(jsonBody[ContractEventsByTxId])
      .summary("Get contract events for a transaction")

  lazy val getEventsByBlockHash
      : BaseEndpoint[(BlockHash, Option[GroupIndex]), ContractEventsByBlockHash] =
    eventsEndpoint
      .in("block-hash")
      .get
      .in(path[BlockHash]("blockHash"))
      .in(query[Option[GroupIndex]]("group"))
      .out(jsonBody[ContractEventsByBlockHash])
      .summary("Get contract events for a block")
}

object Endpoints {
  // scalastyle:off regex
  def error[S <: StatusCode, T <: ApiError[S]: ReadWriter: Schema](
      apiError: ApiError.Companion[S, T],
      matcher: PartialFunction[Any, Boolean]
  )(implicit
      examples: List[Example[T]]
  ): OneOfVariant[T] = {
    oneOfVariantValueMatcher(apiError.statusCode, jsonBody[T].description(apiError.description))(
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
        s"Format 1: `${ALPH.oneAlph}`\n\n" +
          s"Format 2: `x.y ALPH`, where `1 ALPH = ${ALPH.oneAlph}\n\n" +
          s"Field fromPublicKeyType can be  `default` or `bip340-schnorr`"
      )
  }
}
