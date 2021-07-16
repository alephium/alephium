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
import scala.util.Random

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.testkit.{TestActor, TestProbe}
import org.scalatest.EitherValues
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
import org.alephium.protocol.model.{Address, ChainIndex, GroupIndex, NetworkType}
import org.alephium.serde.serialize
import org.alephium.util._
import org.alephium.wallet.WalletApp
import org.alephium.wallet.config.WalletConfig

class RestServerSpec extends AlephiumFutureSpec with EitherValues with NumericHelpers {

  implicit val system: ActorSystem  = ActorSystem("rest-server-spec")
  implicit val ec: ExecutionContext = system.dispatcher

  it should "call GET /blockflow" in new RestServerFixture {
    withServer {
      Get(s"/blockflow?fromTs=0&toTs=0") check { response =>
        response.code is StatusCode.Ok
        response.as[FetchResponse] is dummyFetchResponse
      }

      Get(s"/blockflow?fromTs=10&toTs=0") check { response =>
        response.code is StatusCode.BadRequest
        response.as[ApiError.BadRequest] is ApiError.BadRequest(
          """`fromTs` must be before `toTs`"""
        )
      }
    }
  }

  it should "call GET /blockflow/blocks/<hash>" in new RestServerFixture {
    withServer {
      Get(s"/blockflow/blocks/${dummyBlockHeader.hash.toHexString}") check { response =>
        val chainIndex = ChainIndex.from(dummyBlockHeader.hash)
        if (brokerConfig.contains(chainIndex.from) || brokerConfig.contains(chainIndex.to)) {
          response.code is StatusCode.Ok
          response.as[BlockEntry] is dummyBlockEntry
        } else {
          response.code is StatusCode.BadRequest
        }
      }
    }
  }

  it should "call GET /addresses/<address>/balance" in new RestServerFixture {
    withServer {
      Get(s"/addresses/$dummyKeyAddress/balance") check { response =>
        response.code is StatusCode.Ok
        response.as[Balance] is dummyBalance
      }
    }
  }

  it should "call GET /addresses/<address>/group" in new RestServerFixture {
    withServer {
      Get(s"/addresses/$dummyKeyAddress/group") check { response =>
        response.code is StatusCode.Ok
        response.as[Group] is dummyGroup
      }
    }
  }

  it should "call GET /blockflow/hashes" in new RestServerFixture {
    withServer {
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
  }

  it should "call GET /blockflow/chains" in new RestServerFixture {
    withServer {
      Get(s"/blockflow/chains?fromGroup=1&toGroup=1") check { response =>
        response.code is StatusCode.Ok
        response.as[ChainInfo] is dummyChainInfo
      }
      Get(s"/blockflow/chains?toGroup=1") check { response =>
        response.code is StatusCode.BadRequest
        response.as[ApiError.BadRequest] is ApiError.BadRequest(
          s"Invalid value for: query parameter fromGroup"
        )
      }
      Get(s"/blockflow/chains?fromGroup=1") check { response =>
        response.code is StatusCode.BadRequest
      }
      Get(s"/blockflow/chains?fromGroup=10&toGroup=1") check { response =>
        response.code is StatusCode.BadRequest
      }
      Get(s"/blockflow/chains?fromGroup=1&toGroup=10") check { response =>
        response.code is StatusCode.BadRequest
      }
    }
  }

  it should "call GET /transactions/unconfirmed" in new RestServerFixture {
    withServer {
      Get(s"/transactions/unconfirmed?fromGroup=0&toGroup=0") check { response =>
        response.code is StatusCode.Ok
        response.as[AVector[Tx]] is AVector.empty[Tx]
      }
    }
  }

  it should "call POST /transactions/build" in new MultiRestServerFixture {
    withServers {
      Post(
        s"/transactions/build",
        body = s"""
        |{
        |  "fromPublicKey": "$dummyKeyHex",
        |  "destinations": [
        |    {
        |      "address": "$dummyToAddress",
        |      "amount": "1"
        |    }
        |  ]
        |}
        """.stripMargin
      ) check { response =>
        response.code is StatusCode.Ok
        response.as[BuildTransactionResult] is dummyBuildTransactionResult(
          ServerFixture.dummyTransferTx(dummyTx, AVector((dummyToLockupScript, U256.One, None)))
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
            AVector((dummyToLockupScript, U256.One, Some(TimeStamp.unsafe(1234))))
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
        |      "amount": "1"
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
  }

  it should "call POST /transactions/submit" in new RestServerFixture {
    withServer {
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
  }

  it should "call POST /transactions/sweep-all/build" in new RestServerFixture {
    withServer {
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
  }

  it should "call GET /transactions/status" in new MultiRestServerFixture {
    var txChainIndex: ChainIndex = _
    withServers {
      forAll(hashGen) { txId =>
        servers.foreachWithIndex { case (server, index) =>
          Get(
            s"/transactions/status?txId=${txId.toHexString}",
            server.port
          ) check { response =>
            val status = response.as[TxStatus]
            response.code is StatusCode.Ok
            txChainIndex = ChainIndex.from(status.asInstanceOf[Confirmed].blockHash)
            status is dummyTxStatus
          }

          // scalastyle:off no.equal
          val rightNode = txChainIndex.from.value == index
          // scalastyle:on no.equal

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
  }

  it should "call POST /miners" in new RestServerFixture {
    withServer {
      val address      = Address.fromBase58(dummyKeyAddress, networkType).get
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
  }

  it should "call GET /miners/addresses" in new RestServerFixture {
    withServer {
      val address      = Address.fromBase58(dummyKeyAddress, networkType).get
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
  }

  it should "call PUT /miners/addresses" in new RestServerFixture {
    withServer {
      allHandlersProbe.viewHandler.setAutoPilot(TestActor.NoAutoPilot)

      val newAddresses = AVector.tabulate(config.broker.groups)(i =>
        addressStringGen(GroupIndex.unsafe(i)).sample.get._1
      )
      val body = s"""{"addresses":${writeJs(newAddresses)}}"""

      Put(s"/miners/addresses", body) check { response =>
        val addresses = newAddresses.map(Address.fromBase58(_, networkType).get)
        allHandlersProbe.viewHandler.expectMsg(ViewHandler.UpdateMinerAddresses(addresses))
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
        response.as[ApiError.BadRequest] is ApiError.BadRequest(
          s"Address ${dummyKeyAddress} doesn't belong to group 1"
        )
      }
    }
  }

  it should "call GET /infos/node" in new RestServerFixture {
    withServer {
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
        response.as[NodeInfo] is NodeInfo(isMining = false)
      }

      miner ! Miner.Start

      Get(s"/infos/node") check { response =>
        response.as[NodeInfo] is NodeInfo(isMining = true)
      }

      miner ! Miner.Stop

      Get(s"/infos/node") check { response =>
        response.as[NodeInfo] is NodeInfo(isMining = false)
      }
    }
  }

  it should "call GET /infos/misbehaviors" in new RestServerFixture {
    import MisbehaviorManager._
    withServer {

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
  }

  it should "call POST /infos/misbehaviors" in new RestServerFixture {
    withServer {
      val body = """{"type":"unban","peers":["123.123.123.123"]}"""
      Post(s"/infos/misbehaviors", body) check { response =>
        response.code is StatusCode.Ok
      }
    }
  }

  it should "call GET /docs" in new RestServerFixture {
    withServer {
      Get(s"/docs") check { response =>
        response.code is StatusCode.Ok
      }

      Get(s"/docs/openapi.json") check { response =>
        response.code is StatusCode.Ok

        val openapiPath = ApiModel.getClass.getResource("/openapi.json")
        val expectedOpenapi =
          read[ujson.Value](
            Source
              .fromFile(openapiPath.getPath, "UTF-8")
              .getLines()
              .toSeq
              .mkString("\n")
              .replaceFirst("12973", s"$port")
          )

        val openapi =
          response.as[ujson.Value]

        Get(s"/docs") check { response =>
          response.code is StatusCode.Ok
          openapi is expectedOpenapi
        }

      }
    }
  }

  trait Fixture extends ServerFixture with HttpRouteFixture {
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

    lazy val node = new NodeDummy(
      dummyIntraCliqueInfo,
      dummyNeighborPeers,
      dummyBlock,
      blockFlowProbe.ref,
      allHandlers,
      dummyTx,
      storages,
      cliqueManagerOpt = Some(cliqueManager),
      misbehaviorManagerOpt = Some(misbehaviorManager)
    )
    lazy val blocksExporter = new BlocksExporter(node.blockFlow, rootPath)
    val walletConfig: WalletConfig = WalletConfig(
      None,
      (new java.io.File("")).toPath,
      NetworkType.Devnet,
      Duration.ofMinutesUnsafe(0),
      WalletConfig.BlockFlow("host", 0, 0, Duration.ofMinutesUnsafe(0))
    )

    lazy val walletApp = new WalletApp(walletConfig)
  }

  trait RestServerFixture extends Fixture {
    lazy val port = config.network.restPort
    lazy val server: RestServer =
      RestServer(node, miner, blocksExporter, Some(walletApp.walletServer))

    implicit lazy val apiConfig: ApiConfig     = ApiConfig.load(newConfig)
    implicit lazy val networkType: NetworkType = config.network.networkType

    lazy val blockflowFetchMaxAge = apiConfig.blockflowFetchMaxAge

    def withServer(f: => Any) = {
      try {
        server.start().futureValue
        f
      } finally {
        server.stop().futureValue
      }
    }
  }

  trait MultiRestServerFixture extends Fixture with SocketUtil {

    implicit lazy val networkType: NetworkType = config.network.networkType
    implicit lazy val blockflowFetchMaxAge     = Duration.zero
    lazy val groupNumPerBroker                 = config.broker.groupNumPerBroker

    private def buildPeer(id: Int): (PeerInfo, ApiConfig) = {
      val peerPort = generatePort()

      val address = new InetSocketAddress("127.0.0.1", peerPort)
      //all same port as only `restPort` is used
      val peer = PeerInfo.unsafe(
        id,
        groupNumPerBroker = groupNumPerBroker,
        publicAddress = None,
        privateAddress = address,
        restPort = peerPort,
        wsPort = peerPort,
        minerApiPort = peerPort
      )

      val peerConf = ApiConfig(
        networkInterface = address.getAddress,
        blockflowFetchMaxAge = blockflowFetchMaxAge,
        askTimeout = Duration.ofMinutesUnsafe(1)
      )

      (peer, peerConf)
    }

    private def buildServers(nb: Int) = {
      val peers = (0 to nb - 1).map(buildPeer)

      val intraCliqueInfo = IntraCliqueInfo.unsafe(
        dummyIntraCliqueInfo.id,
        AVector.from(peers.map(_._1)),
        groupNumPerBroker = groupNumPerBroker,
        dummyIntraCliqueInfo.priKey
      )

      AVector.from(peers.zipWithIndex.map { case ((peer, peerConf), id) =>
        val newConfig = config.copy(broker = config.broker.copy(brokerId = id))
        val nodeDummy = new NodeDummy(
          intraCliqueInfo,
          dummyNeighborPeers,
          dummyBlock,
          blockFlowProbe.ref,
          allHandlers,
          dummyTx,
          storages,
          cliqueManagerOpt = Some(cliqueManager),
          misbehaviorManagerOpt = Some(misbehaviorManager)
        )(newConfig)

        new RestServer(
          nodeDummy,
          peer.restPort,
          miner,
          blocksExporter,
          Some(walletApp.walletServer)
        )(
          newConfig.broker,
          peerConf,
          scala.concurrent.ExecutionContext.Implicits.global
        )
      })
    }

    lazy val servers = buildServers(config.broker.groups)

    lazy val port = servers.sample().port

    def withServers(f: => Any) = {
      try {
        servers.foreach(_.start().futureValue)
        f
      } finally {
        servers.foreach(_.stop().futureValue)
      }
    }
  }
}
