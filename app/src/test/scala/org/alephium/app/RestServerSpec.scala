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

package org.alephium.app

import java.net.{InetAddress, InetSocketAddress}

import scala.concurrent._
import scala.io.Source
import scala.util.{Random, Using}

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.testkit.{TestActor, TestProbe}
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach, EitherValues}
import sttp.model.StatusCode

import org.alephium.api.{ApiError, ApiModel}
import org.alephium.api.UtilJson.avectorReadWriter
import org.alephium.api.model._
import org.alephium.app.ServerFixture.NodeDummy
import org.alephium.flow.handler.{TestUtils, ViewHandler}
import org.alephium.flow.mining.Miner
import org.alephium.flow.network.{CliqueManager, InterCliqueManager}
import org.alephium.flow.network.bootstrap._
import org.alephium.flow.network.broker.MisbehaviorManager
import org.alephium.http.HttpFixture._
import org.alephium.http.HttpRouteFixture
import org.alephium.json.Json._
import org.alephium.protocol.{ALPH, Hash}
import org.alephium.protocol.model.{Address, ChainIndex, GroupIndex, ReleaseVersion, TxGenerators}
import org.alephium.protocol.model.UnsignedTransaction.TxOutputInfo
import org.alephium.protocol.vm.LockupScript
import org.alephium.serde.serialize
import org.alephium.util._
import org.alephium.wallet.WalletApp
import org.alephium.wallet.config.WalletConfig

//scalastyle:off file.size.limit
abstract class RestServerSpec(val nbOfNodes: Int, val apiKey: Option[ApiKey] = None)
    extends AlephiumFutureSpec
    with RestServerFixture
    with TxGenerators
    with EitherValues
    with NumericHelpers
    with BeforeAndAfterAll
    with BeforeAndAfterEach {

  override def beforeAll() = {
    super.beforeAll()
    servers.foreach(_.start().futureValue)
  }

  override def afterAll() = {
    super.afterAll()
    servers.foreach(_.stop().futureValue)
  }

  override def beforeEach() = {
    super.beforeEach()
    interCliqueSynced = true
  }

  it should "call GET /blockflow" in {
    Get(blockflowFromTo(0, 0)) check { response =>
      response.code is StatusCode.Ok
      response.as[FetchResponse] is dummyFetchResponse
    }

    Get(blockflowFromTo(10, 0)) check { response =>
      response.code is StatusCode.BadRequest
      response.as[ApiError.BadRequest] is ApiError.BadRequest(
        """Invalid value (`fromTs` must be before `toTs`)"""
      )
    }
  }

  it should "call GET /blockflow/blocks/<hash>" in {
    servers.foreach { server =>
      Get(s"/blockflow/blocks/${dummyBlockHeader.hash.toHexString}", server.port) check {
        response =>
          val chainIndex = ChainIndex.from(dummyBlockHeader.hash)
          if (
            server.brokerConfig
              .contains(chainIndex.from) || server.brokerConfig.contains(chainIndex.to)
          ) {
            response.code is StatusCode.Ok
            response.as[BlockEntry] is dummyBlockEntry
          } else {
            response.code is StatusCode.BadRequest
          }
      }
    }
  }

  it should "call GET /blockflow/headers/<hash>" in {
    servers.foreach { server =>
      Get(s"/blockflow/headers/${dummyBlockHeader.hash.toHexString}", server.port) check {
        response =>
          response.code is StatusCode.Ok
          response.as[BlockHeaderEntry] is BlockHeaderEntry.from(
            dummyBlockHeader,
            dummyBlockEntry.height
          )
      }
    }
  }

  it should "call GET /addresses/<address>/balance" in {
    val group = LockupScript.p2pkh(dummyKey).groupIndex(brokerConfig)
    Get(s"/addresses/$dummyKeyAddress/balance", getPort(group)) check { response =>
      response.code is StatusCode.Ok
      response.as[Balance] is dummyBalance
    }
    Get(s"/addresses/$dummyKeyAddress/balance?utxosLimit=0", getPort(group)) check { response =>
      response.code is StatusCode.Ok
      response
        .as[Balance]
        .warning
        .get is "Result might not include all utxos and is maybe unprecise"
    }
  }

  it should "call GET /addresses/<address>/group" in {
    Get(s"/addresses/$dummyKeyAddress/group") check { response =>
      response.code is StatusCode.Ok
      response.as[Group] is dummyGroup
    }
    Get(s"/addresses/${dummyContractAddress}/group") check { response =>
      response.code is StatusCode.Ok
      response.as[Group] is dummyContractGroup
    }
  }

  it should "call GET /addresses/<address>/utxos" in {
    val group = LockupScript.p2pkh(dummyKey).groupIndex(brokerConfig)
    Get(s"/addresses/$dummyKeyAddress/utxos", getPort(group)) check { response =>
      response.code is StatusCode.Ok
      val utxos = response.as[UTXOs]
      utxos.utxos.length is 2
      utxos.warning is None
    }
  }

  it should "call GET /blockflow/hashes" in {
    Get(s"/blockflow/hashes?fromGroup=1&toGroup=1&height=1") check { response =>
      response.code is StatusCode.Ok
      response.as[HashesAtHeight] is dummyHashesAtHeight
    }
    Get(s"/blockflow/hashes?toGroup=1&height=1") check { response =>
      response.code is StatusCode.BadRequest
    }
    Get(s"/blockflow/hashes?fromGroup=1&height=1") check { response =>
      response.code is StatusCode.BadRequest
    }
    Get(s"/blockflow/hashes?fromGroup=1&toGroup=1") check { response =>
      response.code is StatusCode.BadRequest
    }
    Get(s"/blockflow/hashes?fromGroup=10&toGroup=1&height=1") check { response =>
      response.code is StatusCode.BadRequest
    }
    Get(s"/blockflow/hashes?fromGroup=1&toGroup=10&height=1") check { response =>
      response.code is StatusCode.BadRequest
    }
    Get(s"/blockflow/hashes?fromGroup=1&toGroup=10&height=-1") check { response =>
      response.code is StatusCode.BadRequest
    }
  }

  it should "call GET /transactions/unconfirmed" in {
    Get(s"/transactions/unconfirmed?fromGroup=0&toGroup=0") check { response =>
      response.code is StatusCode.Ok
      response.as[AVector[UnconfirmedTransactions]] is AVector.empty[UnconfirmedTransactions]
    }
  }

  it should "call GET /blockflow/chain-info" in {
    Get(s"/blockflow/chain-info?fromGroup=1&toGroup=1") check { response =>
      response.code is StatusCode.Ok
      response.as[ChainInfo] is dummyChainInfo
    }
    Get(s"/blockflow/chain-info?toGroup=1") check { response =>
      response.code is StatusCode.BadRequest
      response.as[ApiError.BadRequest] is ApiError.BadRequest(
        s"Invalid value for: query parameter fromGroup"
      )
    }
    Get(s"/blockflow/chain-info?fromGroup=1") check { response =>
      response.code is StatusCode.BadRequest
    }
    Get(s"/blockflow/chain-info?fromGroup=10&toGroup=1") check { response =>
      response.code is StatusCode.BadRequest
    }
    Get(s"/blockflow/chain-info?fromGroup=1&toGroup=10") check { response =>
      response.code is StatusCode.BadRequest
    }
  }

  it should "call POST /transactions/build" in {
    Post(
      s"/transactions/build",
      body = s"""
        |{
        |  "fromPublicKey": "$dummyKeyHex",
        |  "destinations": [
        |    {
        |      "address": "$dummyToAddress",
        |      "amount": "1",
        |      "tokens": []
        |    }
        |  ]
        |}
        """.stripMargin
    ) check { response =>
      response.code is StatusCode.Ok
      response.as[BuildTransactionResult] is dummyBuildTransactionResult(
        ServerFixture.dummyTransferTx(
          dummyTx,
          AVector(TxOutputInfo(dummyToLockupScript, U256.One, AVector.empty, None))
        )
      )
    }
    Post(
      s"/transactions/build",
      body = s"""
        |{
        |  "fromPublicKey": "$dummyKeyHex",
        |  "destinations": [
        |    {
        |      "address": "$dummyToAddress",
        |      "amount": "1",
        |      "tokens": [],
        |      "lockTime": "1234"
        |    }
        |  ]
        |}
        """.stripMargin
    ) check { response =>
      response.code is StatusCode.Ok
      response.as[BuildTransactionResult] is dummyBuildTransactionResult(
        ServerFixture.dummyTransferTx(
          dummyTx,
          AVector(
            TxOutputInfo(
              dummyToLockupScript,
              U256.One,
              AVector.empty,
              Some(TimeStamp.unsafe(1234))
            )
          )
        )
      )
    }

    interCliqueSynced = false

    Post(
      s"/transactions/build",
      body = s"""
        |{
        |  "fromPublicKey": "$dummyKeyHex",
        |  "destinations": [
        |    {
        |      "address": "$dummyToAddress",
        |      "amount": "1",
        |      "tokens": []
        |    }
        |  ]
        |}
        """.stripMargin
    ) check { response =>
      response.code is StatusCode.ServiceUnavailable
      response.as[ApiError.ServiceUnavailable] is ApiError.ServiceUnavailable(
        "The clique is not synced"
      )
    }
  }

  it should "call POST /transactions/submit" in {
    val tx =
      s"""{"unsignedTx":"${Hex.toHexString(
        serialize(dummyTx.unsigned)
      )}","signature":"${dummySignature.toHexString}","publicKey":"dummyKey),"}"""
    Post(s"/transactions/submit", tx) check { response =>
      response.code is StatusCode.Ok
      response.as[TxResult] is dummyTransferResult
    }

    interCliqueSynced = false

    Post(s"/transactions/submit", tx) check { response =>
      response.code is StatusCode.ServiceUnavailable
      response.as[ApiError.ServiceUnavailable] is ApiError.ServiceUnavailable(
        "The clique is not synced"
      )
    }
  }

  it should "call POST /transactions/sweep-all/build" in {
    Post(
      s"/transactions/sweep-all/build",
      body = s"""
        |{
        |  "fromPublicKey": "$dummyKeyHex",
        |  "toAddress": "$dummyToAddress"
        |}
        """.stripMargin
    ) check { response =>
      response.code is StatusCode.Ok
      response.as[BuildTransactionResult] is dummyBuildTransactionResult(
        ServerFixture.dummySweepAllTx(dummyTx, dummyToLockupScript, None)
      )
    }
    Post(
      s"/transactions/sweep-all/build",
      body = s"""
        |{
        |  "fromPublicKey": "$dummyKeyHex",
        |  "toAddress": "$dummyToAddress",
        |  "lockTime": "1234"
        |}
        """.stripMargin
    ) check { response =>
      response.code is StatusCode.Ok
      response.as[BuildTransactionResult] is dummyBuildTransactionResult(
        ServerFixture.dummySweepAllTx(dummyTx, dummyToLockupScript, Some(TimeStamp.unsafe(1234)))
      )
    }

    interCliqueSynced = false

    Post(
      s"/transactions/sweep-all/build",
      body = s"""
        |{
        |  "fromPublicKey": "$dummyKeyHex",
        |  "toAddress": "$dummyToAddress"
        |}
        """.stripMargin
    ) check { response =>
      response.code is StatusCode.ServiceUnavailable
      response.as[ApiError.ServiceUnavailable] is ApiError.ServiceUnavailable(
        "The clique is not synced"
      )
    }
  }

  it should "call GET /transactions/status" in {
    var txChainIndex: ChainIndex = ChainIndex.unsafe(0, 0)
    forAll(hashGen) { txId =>
      servers.foreach { server =>
        Get(
          s"/transactions/status?txId=${txId.toHexString}",
          server.port
        ) check { response =>
          val status = response.as[TxStatus]
          response.code is StatusCode.Ok
          txChainIndex = ChainIndex.from(status.asInstanceOf[Confirmed].blockHash)
          status is dummyTxStatus
        }

        val rightNode = server.node.config.broker.contains(txChainIndex.from)

        Get(
          s"/transactions/status?txId=${txId.toHexString}&fromGroup=${txChainIndex.from.value}&toGroup=${txChainIndex.to.value}",
          server.port
        ) check { response =>
          if (rightNode) {
            val status = response.as[TxStatus]
            response.code is StatusCode.Ok
            status is dummyTxStatus
          } else {
            response.code is StatusCode.BadRequest
            response.as[ApiError.BadRequest] is ApiError.BadRequest(
              s"${txId.toHexString} belongs to other groups"
            )
          }
        }

        Get(
          s"/transactions/status?txId=${txId.toHexString}&fromGroup=${txChainIndex.from.value}",
          server.port
        ) check { response =>
          if (rightNode) {
            val status = response.as[TxStatus]
            response.code is StatusCode.Ok
            status is dummyTxStatus
          } else {
            response.code is StatusCode.Ok
            response.as[TxStatus] is NotFound
          }
        }

        Get(
          s"/transactions/status?txId=${txId.toHexString}&toGroup=${txChainIndex.to.value}",
          server.port
        ) check { response =>
          if (rightNode) {
            val status = response.as[TxStatus]
            response.code is StatusCode.Ok
            status is dummyTxStatus
          } else {
            response.code is StatusCode.Ok
            response.as[TxStatus] is NotFound
          }
        }
      }
    }
  }

  it should "call POST /multisig/address" in {
    lazy val (_, dummyKey2, _) = addressStringGen(
      GroupIndex.unsafe(1)
    ).sample.get

    lazy val dummyKeyHex2 = dummyKey2.toHexString

    Post(
      s"/multisig/address",
      body = s"""
        |{
        |  "keys": [
        | "$dummyKeyHex",
        | "$dummyKeyHex2"
        |],
        |  "mrequired": 1
        |}
        """.stripMargin
    ) check { response =>
      response.code is StatusCode.Ok

      val result   = response.as[BuildMultisigAddress.Result]
      val expected = ServerFixture.p2mpkhAddress(AVector(dummyKeyHex, dummyKeyHex2), 1)

      result.address is expected
    }
  }

  it should "call POST /multisig/build" in {
    lazy val (_, dummyKey2, _) = addressStringGen(
      GroupIndex.unsafe(1)
    ).sample.get

    lazy val dummyKeyHex2 = dummyKey2.toHexString

    val address = ServerFixture.p2mpkhAddress(AVector(dummyKeyHex, dummyKeyHex2), 1)

    Post(
      s"/multisig/build",
      body = s"""
        |{
        |  "fromAddress": "${address.toBase58}",
        |  "fromPublicKeys": ["$dummyKeyHex"],
        |  "destinations": [
        |    {
        |      "address": "$dummyToAddress",
        |      "amount": "1",
        |      "tokens": []
        |    }
        |  ]
        |}
        """.stripMargin
    ) check { response =>
      response.code is StatusCode.Ok
      response.as[BuildTransactionResult] is dummyBuildTransactionResult(
        ServerFixture.dummyTransferTx(
          dummyTx,
          AVector(TxOutputInfo(dummyToLockupScript, U256.One, AVector.empty, None))
        )
      )
    }
  }

  it should "call POST /multisig/submit" in {
    val tx =
      s"""{"unsignedTx":"${Hex.toHexString(
        serialize(dummyTx.unsigned)
      )}","signatures":["${dummySignature.toHexString}"]}"""
    Post(s"/multisig/submit", tx) check { response =>
      response.code is StatusCode.Ok
      response.as[TxResult] is dummyTransferResult
    }
  }

  it should "call POST /miners" in {
    val address      = Address.asset(dummyKeyAddress).get
    val lockupScript = address.lockupScript
    allHandlersProbe.viewHandler.setAutoPilot((sender: ActorRef, msg: Any) =>
      msg match {
        case ViewHandler.GetMinerAddresses =>
          sender ! None
          TestActor.KeepRunning
        case InterCliqueManager.IsSynced =>
          sender ! InterCliqueManager.SyncedResult(true)
          TestActor.KeepRunning
      }
    )

    Post(s"/miners?action=start-mining") check { response =>
      minerProbe.expectNoMessage()
      response.code is StatusCode.InternalServerError
      response.as[ApiError.InternalServerError] is
        ApiError.InternalServerError("Miner addresses are not set up")
    }

    allHandlersProbe.viewHandler.setAutoPilot((sender: ActorRef, msg: Any) =>
      msg match {
        case ViewHandler.GetMinerAddresses =>
          sender ! Some(AVector(lockupScript))
          TestActor.KeepRunning
        case InterCliqueManager.IsSynced =>
          sender ! InterCliqueManager.SyncedResult(interCliqueSynced)
          TestActor.KeepRunning
      }
    )

    Post(s"/miners?action=start-mining") check { response =>
      minerProbe.expectMsg(Miner.Start)
      response.code is StatusCode.Ok
      response.as[Boolean] is true
    }

    Post(s"/miners?action=stop-mining") check { response =>
      minerProbe.expectMsg(Miner.Stop)
      response.code is StatusCode.Ok
      response.as[Boolean] is true
    }

    interCliqueSynced = false

    Post(s"/miners?action=start-mining") check { response =>
      response.code is StatusCode.ServiceUnavailable
      response.as[ApiError.ServiceUnavailable] is ApiError.ServiceUnavailable(
        "The clique is not synced"
      )
    }
  }

  it should "call GET /miners/addresses" in {
    val address      = Address.asset(dummyKeyAddress).get
    val lockupScript = address.lockupScript

    allHandlersProbe.viewHandler.setAutoPilot((sender: ActorRef, msg: Any) =>
      msg match {
        case ViewHandler.GetMinerAddresses =>
          sender ! Some(AVector(lockupScript))
          TestActor.NoAutoPilot
      }
    )

    Get(s"/miners/addresses") check { response =>
      response.code is StatusCode.Ok
      response.as[MinerAddresses] is MinerAddresses(AVector(address))
    }

    allHandlersProbe.viewHandler.setAutoPilot((sender: ActorRef, msg: Any) =>
      msg match {
        case ViewHandler.GetMinerAddresses =>
          sender ! None
          TestActor.NoAutoPilot
      }
    )

    Get(s"/miners/addresses") check { response =>
      response.code is StatusCode.InternalServerError
      response.as[ApiError.InternalServerError] is
        ApiError.InternalServerError("Miner addresses are not set up")
    }
  }

  it should "call PUT /miners/addresses" in {
    allHandlersProbe.viewHandler.setAutoPilot(TestActor.NoAutoPilot)

    val newAddresses = AVector.tabulate(config.broker.groups)(i =>
      addressStringGen(GroupIndex.unsafe(i)).sample.get._1
    )
    val body = s"""{"addresses":${writeJs(newAddresses)}}"""

    Put(s"/miners/addresses", body) check { response =>
      val addresses = newAddresses.map(Address.asset(_).get)
      allHandlersProbe.viewHandler.fishForSpecificMessage()(_ =>
        ViewHandler.UpdateMinerAddresses(addresses)
      )
      response.code is StatusCode.Ok
    }

    val notEnoughAddressesBody = s"""{"addresses":["${dummyKeyAddress}"]}"""
    Put(s"/miners/addresses", notEnoughAddressesBody) check { response =>
      response.code is StatusCode.BadRequest
      response.as[ApiError.BadRequest] is ApiError.BadRequest(
        s"Wrong number of addresses, expected ${config.broker.groups}, got 1"
      )
    }

    val wrongGroup     = AVector.tabulate(config.broker.groups)(_ => dummyKeyAddress)
    val wrongGroupBody = s"""{"addresses":${writeJs(wrongGroup)}}"""
    Put(s"/miners/addresses", wrongGroupBody) check { response =>
      response.code is StatusCode.BadRequest
      val errorGroup = dummyGroup.group match {
        case 0 => 1
        case _ => 0
      }
      response.as[ApiError.BadRequest] is ApiError.BadRequest(
        s"Address ${dummyKeyAddress} doesn't belong to group $errorGroup"
      )
    }
  }

  it should "call GET /infos/node" in {
    val buildInfo = NodeInfo.BuildInfo(BuildInfo.releaseVersion, BuildInfo.commitId)
    minerProbe.setAutoPilot(new TestActor.AutoPilot {
      var miningStarted: Boolean = false
      def run(sender: ActorRef, msg: Any): TestActor.AutoPilot =
        msg match {
          case Miner.IsMining =>
            sender ! miningStarted
            TestActor.KeepRunning
          case Miner.Start =>
            miningStarted = true
            TestActor.KeepRunning
          case Miner.Stop =>
            miningStarted = false
            TestActor.KeepRunning
        }

    })

    Get(s"/infos/node") check { response =>
      response.code is StatusCode.Ok
      response.as[NodeInfo] is NodeInfo(ReleaseVersion.current, buildInfo)
    }
  }

  it should "call GET /infos/misbehaviors" in {
    import MisbehaviorManager._

    val inetAddress = InetAddress.getByName("127.0.0.1")
    val ts          = TimeStamp.now()

    misbehaviorManagerProbe.setAutoPilot(new TestActor.AutoPilot {
      def run(sender: ActorRef, msg: Any): TestActor.AutoPilot =
        msg match {
          case GetPeers =>
            sender ! Peers(AVector(Peer(inetAddress, Banned(ts))))
            TestActor.NoAutoPilot
        }
    })

    Get(s"/infos/misbehaviors") check { response =>
      response.code is StatusCode.Ok
      response.as[AVector[PeerMisbehavior]] is AVector(
        PeerMisbehavior(inetAddress, PeerStatus.Banned(ts))
      )
    }
  }

  it should "call POST /infos/misbehaviors" in {
    val body = """{"type":"unban","peers":["123.123.123.123"]}"""
    Post(s"/infos/misbehaviors", body) check { response =>
      response.code is StatusCode.Ok
    }
  }

  it should "call GET /contracts/<address>/state" in {
    0.until(brokerConfig.groups).filter(_ != dummyContractGroup.group).foreach { group =>
      Get(s"/contracts/${dummyContractAddress}/state?group=${group}") check { response =>
        response.code is StatusCode.InternalServerError
      }
    }
    Get(s"/contracts/${dummyContractAddress}/state?group=${dummyContractGroup.group}") check {
      response =>
        response.code is StatusCode.Ok
        response.as[ContractStateResult] is ContractStateResult(AVector(Val.U256(U256.Zero)))
    }
  }

  it should "call GET /docs" in {
    Get(s"/docs") check { response =>
      response.code is StatusCode.Ok
    }

    Get(s"/docs/openapi.json") check { response =>
      response.code is StatusCode.Ok

      val openapiPath = ApiModel.getClass.getResource("/openapi.json")
      val expectedOpenapi =
        read[ujson.Value](
          Using(Source.fromFile(openapiPath.getPath, "UTF-8")) { source =>
            source.getLines().mkString("\n").replaceFirst("12973", s"$port")
          }.get
        )

      val openapi =
        removeField("security", removeField("securitySchemes", response.as[ujson.Value]))

      Get(s"/docs") check { response =>
        response.code is StatusCode.Ok
        openapi is expectedOpenapi
      }

    }
  }

  it should "correctly use the api-key" in {
    val newApiKey = apiKey match {
      case Some(_) => None
      case None    => Some(Hash.random.toHexString)
    }

    Get(blockflowFromTo(0, 0), apiKey = newApiKey) check { response =>
      response.code is StatusCode.Unauthorized
      if (newApiKey.isDefined) {
        response.as[ApiError.Unauthorized] is ApiError.Unauthorized(
          "Api key not configured in server"
        )
      } else {
        response.as[ApiError.Unauthorized] is ApiError.Unauthorized("Missing api key")
      }
    }
  }

  it should "validate the api-key" in {
    val newApiKey = apiKey.map(_ => Hash.random.toHexString)

    Get(blockflowFromTo(0, 0), apiKey = newApiKey) check { response =>
      if (apiKey.isDefined) {
        response.code is StatusCode.Unauthorized
        response.as[ApiError.Unauthorized] is ApiError.Unauthorized("Wrong api key")
      } else {
        response.code is StatusCode.Ok
      }
    }
  }
}

trait RestServerFixture extends ServerFixture with HttpRouteFixture {
  val nbOfNodes: Int
  val apiKey: Option[ApiKey]
  implicit val system: ActorSystem  = ActorSystem("rest-server-spec")
  implicit val ec: ExecutionContext = system.dispatcher

  override val configValues = Map(("alephium.broker.broker-num", nbOfNodes)) ++ apiKey
    .map(key => Map(("alephium.api.api-key", key.value)))
    .getOrElse(Map.empty)

  lazy val minerProbe                      = TestProbe()
  lazy val miner                           = ActorRefT[Miner.Command](minerProbe.ref)
  lazy val (allHandlers, allHandlersProbe) = TestUtils.createAllHandlersProbe

  var selfCliqueSynced  = true
  var interCliqueSynced = true

  allHandlersProbe.viewHandler.setAutoPilot((sender: ActorRef, msg: Any) =>
    msg match {
      case InterCliqueManager.IsSynced =>
        sender ! InterCliqueManager.SyncedResult(interCliqueSynced)
        TestActor.KeepRunning
    }
  )
  lazy val cliqueManager: ActorRefT[CliqueManager.Command] =
    ActorRefT.build(
      system,
      Props(new BaseActor {
        override def receive: Receive = { case CliqueManager.IsSelfCliqueReady =>
          sender() ! selfCliqueSynced
        }
      }),
      s"clique-manager-${Random.nextInt()}"
    )

  lazy val blockFlowProbe = TestProbe()

  lazy val misbehaviorManagerProbe = TestProbe()
  lazy val misbehaviorManager      = ActorRefT[MisbehaviorManager.Command](misbehaviorManagerProbe.ref)

  implicit lazy val apiConfig: ApiConfig = ApiConfig.load(newConfig)

  lazy val node = new NodeDummy(
    dummyIntraCliqueInfo,
    dummyNeighborPeers,
    dummyBlock,
    blockFlowProbe.ref,
    allHandlers,
    dummyTx,
    counterContract,
    storages,
    cliqueManagerOpt = Some(cliqueManager),
    misbehaviorManagerOpt = Some(misbehaviorManager)
  )
  lazy val blocksExporter = new BlocksExporter(node.blockFlow, rootPath)
  val walletConfig: WalletConfig = WalletConfig(
    None,
    (new java.io.File("")).toPath,
    Duration.ofMinutesUnsafe(0),
    apiConfig.apiKey,
    WalletConfig.BlockFlow("host", 0, 0, Duration.ofMinutesUnsafe(0), apiConfig.apiKey)
  )

  lazy val walletApp = new WalletApp(walletConfig)

  implicit lazy val blockflowFetchMaxAge = Duration.zero

  private def buildPeer(id: Int): (PeerInfo, ApiConfig) = {
    val peerPort = generatePort()

    val address = new InetSocketAddress("127.0.0.1", peerPort)
    //all same port as only `restPort` is used
    val peer = PeerInfo.unsafe(
      id,
      groupNumPerBroker = config.broker.groupNumPerBroker,
      publicAddress = None,
      privateAddress = address,
      restPort = peerPort,
      wsPort = peerPort,
      minerApiPort = peerPort
    )

    val peerConf = ApiConfig(
      networkInterface = address.getAddress,
      blockflowFetchMaxAge = blockflowFetchMaxAge,
      askTimeout = Duration.ofMinutesUnsafe(1),
      apiConfig.apiKey,
      ALPH.oneAlph
    )

    (peer, peerConf)
  }

  def blockflowFromTo(from: Long, to: Long): String = {
    s"/blockflow?fromTs=$from&toTs=$to"
  }

  private def buildServers(nb: Int) = {
    val peers = (0 until nb).map(buildPeer)

    val intraCliqueInfo = IntraCliqueInfo.unsafe(
      dummyIntraCliqueInfo.id,
      AVector.from(peers.map(_._1)),
      groupNumPerBroker = config.broker.groupNumPerBroker,
      dummyIntraCliqueInfo.priKey
    )

    AVector.from(peers.zipWithIndex.map { case ((peer, peerConf), id) =>
      val serverConfig = config.copy(broker = config.broker.copy(brokerId = id))
      val nodeDummy = new NodeDummy(
        intraCliqueInfo,
        dummyNeighborPeers,
        dummyBlock,
        blockFlowProbe.ref,
        allHandlers,
        dummyTx,
        dummyContract,
        storages,
        cliqueManagerOpt = Some(cliqueManager),
        misbehaviorManagerOpt = Some(misbehaviorManager)
      )(serverConfig)

      new RestServer(
        nodeDummy,
        peer.restPort,
        miner,
        blocksExporter,
        Some(walletApp.walletServer)
      )(
        serverConfig.broker,
        peerConf,
        scala.concurrent.ExecutionContext.Implicits.global
      )
    })
  }

  lazy val servers = buildServers(nbOfNodes)

  override lazy val port        = servers.sample().port
  override lazy val maybeApiKey = apiKey.map(_.value)

  def getPort(group: GroupIndex): Int =
    servers.find(_.node.config.broker.contains(group)).get.port

  // scalastyle:off no.equal
  def removeField(name: String, json: ujson.Value): ujson.Value = {
    @SuppressWarnings(Array("org.wartremover.warts.Recursion"))
    def rec(json: ujson.Value): ujson.Value = {
      json match {
        case obj: ujson.Obj =>
          ujson.Obj.from(
            obj.value.filterNot { case (key, _) => key == name }.map { case (key, value) =>
              key -> rec(value)
            }
          )

        case arr: ujson.Arr =>
          val newValues = arr.value.map { value =>
            rec(value)
          }
          ujson.Arr.from(newValues)

        case x => x
      }
    }
    json match {
      case ujson.Null => ujson.Null
      case other      => rec(other)
    }
  }
}

class RestServerSpec1Node  extends RestServerSpec(1)
class RestServerSpec3Nodes extends RestServerSpec(3)
class RestServerSpecApiKey
    extends RestServerSpec(
      3,
      Some(ApiKey.unsafe("74beb7e20967727763f3c88a1ef596e7b22049047cc6fa8ea27358b32c68377"))
    )
