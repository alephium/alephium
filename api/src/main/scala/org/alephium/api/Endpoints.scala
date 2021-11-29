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

import com.typesafe.scalalogging.StrictLogging
import sttp.model.StatusCode
import sttp.tapir._
import sttp.tapir.EndpointIO.Example
import sttp.tapir.EndpointOutput.OneOfMapping
import sttp.tapir.generic.auto._

import org.alephium.api.TapirCodecs
import org.alephium.api.TapirSchemasLike
import org.alephium.api.UtilJson.avectorReadWriter
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
      .and(query[TimeStamp]("toTs"))
      .map({ case (from, to) => TimeInterval(from, to) })(timeInterval =>
        (timeInterval.from, timeInterval.to)
      )
      .validate(Validator.custom { timeInterval =>
        if (timeInterval.from > timeInterval.to) {
          List(ValidationError.Custom(timeInterval, s"`fromTs` must be before `toTs`"))
        } else {
          List.empty
        }
      })

  private lazy val chainIndexQuery: EndpointInput[ChainIndex] =
    query[GroupIndex]("fromGroup")
      .and(query[GroupIndex]("toGroup"))
      .map({ case (from, to) => ChainIndex(from, to) })(chainIndex =>
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

  private val blockflowEndpoint: BaseEndpoint[Unit, Unit] =
    baseEndpoint
      .in("blockflow")
      .tag("Blockflow")

  private val utilsEndpoint: BaseEndpoint[Unit, Unit] =
    baseEndpoint
      .in("utils")
      .tag("Utils")

  val getNodeInfo: BaseEndpoint[Unit, NodeInfo] =
    infosEndpoint.get
      .in("node")
      .out(jsonBody[NodeInfo])
      .summary("Get info about that node")

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
      .summary("Unban given peers")

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

  val buildSweepAllTransaction: BaseEndpoint[BuildSweepAllTransaction, BuildTransactionResult] =
    transactionsEndpoint.post
      .in("sweep-all")
      .in("build")
      .in(jsonBody[BuildSweepAllTransaction])
      .out(jsonBody[BuildTransactionResult])
      .summary("Build an unsigned transaction to send all unlocked balanced to an address")

  val submitTransaction: BaseEndpoint[SubmitTransaction, TxResult] =
    transactionsEndpoint.post
      .in("submit")
      .in(jsonBody[SubmitTransaction])
      .out(jsonBody[TxResult])
      .summary("Submit a signed transaction")

  val buildMultisigAddress: BaseEndpoint[BuildMultisigAddress, BuildMultisigAddress.Result] =
    multisigEndpoint.post
      .in("address")
      .in(jsonBodyWithAlph[BuildMultisigAddress])
      .out(jsonBody[BuildMultisigAddress.Result])
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

  val decodeUnsignedTransaction: BaseEndpoint[DecodeTransaction, Tx] =
    transactionsEndpoint.post
      .in("decode")
      .in(jsonBody[DecodeTransaction])
      .out(jsonBody[Tx])
      .summary("Decode an unsigned transaction")

  val minerAction: BaseEndpoint[MinerAction, Boolean] =
    minersEndpoint.post
      .in(query[MinerAction]("action").examples(minerActionExamples))
      .out(jsonBody[Boolean])
      .summary("Execute an action on miners")

  val minerListAddresses: BaseEndpoint[Unit, MinerAddresses] =
    minersEndpoint.get
      .in("addresses")
      .out(jsonBody[MinerAddresses])
      .summary("List miner's addresses")

  val minerUpdateAddresses: BaseEndpoint[MinerAddresses, Unit] =
    minersEndpoint.put
      .in("addresses")
      .in(jsonBody[MinerAddresses])
      .summary("Update miner's addresses")

  val compileScript: BaseEndpoint[Compile.Script, CompileResult] =
    contractsEndpoint.post
      .in("compile-script")
      .in(jsonBody[Compile.Script])
      .out(jsonBody[CompileResult])
      .summary("Compile a script")

  val buildScript: BaseEndpoint[BuildScript, BuildScriptResult] =
    contractsEndpoint.post
      .in("build-script")
      .in(jsonBody[BuildScript])
      .out(jsonBody[BuildScriptResult])
      .summary("Build an unsigned script")

  val compileContract: BaseEndpoint[Compile.Contract, CompileResult] =
    contractsEndpoint.post
      .in("compile-contract")
      .in(jsonBody[Compile.Contract])
      .out(jsonBody[CompileResult])
      .summary("Compile a smart contract")

  val buildContract: BaseEndpoint[BuildContract, BuildContractResult] =
    contractsEndpoint.post
      .in("build-contract")
      .in(jsonBody[BuildContract])
      .out(jsonBody[BuildContractResult])
      .summary("Build an unsigned contract")

  lazy val contractState: BaseEndpoint[(Address.Contract, GroupIndex), ContractStateResult] =
    contractsEndpoint.get
      .in(path[Address.Contract]("address"))
      .in("state")
      .in(query[GroupIndex]("group"))
      .out(jsonBody[ContractStateResult])
      .summary("Get contract state")

  val exportBlocks: BaseEndpoint[ExportFile, Unit] =
    baseEndpoint.post
      .in("export-blocks")
      .in(jsonBody[ExportFile])
      .summary("Exports all the blocks")

  val metrics: BaseEndpoint[Unit, String] =
    baseEndpoint.get
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
