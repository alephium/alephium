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
import org.alephium.flow.client.Miner
import org.alephium.flow.model.BlockTemplate
import org.alephium.flow.network.{CliqueManager, InterCliqueManager}
import org.alephium.http.HttpFixture._
import org.alephium.http.HttpRouteFixture
import org.alephium.json.Json._
import org.alephium.protocol.{BlockHash, Hash}
import org.alephium.protocol.model.{Address, ChainIndex, GroupIndex, NetworkType, Target}
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

      Get(s"/blockflow?fromTs=10&toTs=0}") check { response =>
        response.code is StatusCode.BadRequest
        response.as[ApiError.BadRequest] is ApiError.BadRequest(
          """Invalid value for: query parameter toTs (For input string: "0}": 0})"""
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

  it should "call GET /transactions/build" in new RestServerFixture {
    withServer {
      Get(
        s"/transactions/build?fromKey=$dummyKey&toAddress=$dummyToAddress&value=1"
      ) check { response =>
        response.code is StatusCode.Ok
        response.as[BuildTransactionResult] is dummyBuildTransactionResult
      }
      Get(
        s"/transactions/build?fromKey=$dummyKey&toAddress=$dummyToAddress&lockTime=1234&value=1"
      ) check { response =>
        response.code is StatusCode.Ok
        response.as[BuildTransactionResult] isnot dummyBuildTransactionResult
      }

      interCliqueSynced = false

      Get(
        s"/transactions/build?fromKey=$dummyKey&toAddress=$dummyToAddress&value=1"
      ) check { response =>
        response.code is StatusCode.ServiceUnavailable
        response.as[ApiError.ServiceUnavailable] is ApiError.ServiceUnavailable(
          "The clique is not synced"
        )
      }
    }
  }

  it should "call POST /transactions/send" in new RestServerFixture {
    withServer {
      val tx =
        s"""{"unsignedTx":"${Hex.toHexString(
          serialize(dummyTx.unsigned)
        )}","signature":"${dummySignature.toHexString}","publicKey":"dummyKey),"}"""
      Post(s"/transactions/send", tx) check { response =>
        response.code is StatusCode.Ok
        response.as[TxResult] is dummyTransferResult
      }

      interCliqueSynced = false

      Post(s"/transactions/send", tx) check { response =>
        response.code is StatusCode.ServiceUnavailable
        response.as[ApiError.ServiceUnavailable] is ApiError.ServiceUnavailable(
          "The clique is not synced"
        )
      }
    }
  }

  it should "call GET /transactions/status" in new RestServerFixture {
    withServer {
      Get(
        s"/transactions/status?txId=${Hash.zero.toHexString}&fromGroup=0&toGroup=1"
      ) check { response =>
        response.code is StatusCode.Ok
        response.as[TxStatus] is dummyTxStatus
      }
    }
  }

  it should "call POST /miners" in new RestServerFixture {
    withServer {
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

      minerProbe.setAutoPilot(new TestActor.AutoPilot {
        def run(sender: ActorRef, msg: Any): TestActor.AutoPilot =
          msg match {
            case Miner.GetAddresses =>
              sender ! AVector(lockupScript)
              TestActor.NoAutoPilot
          }
      })

      Get(s"/miners/addresses") check { response =>
        response.code is StatusCode.Ok
        response.as[MinerAddresses] is MinerAddresses(AVector(address))
      }
    }
  }

  it should "call PUT /miners/addresses" in new RestServerFixture {
    withServer {
      val newAddresses = AVector.tabulate(config.broker.groups)(i =>
        addressStringGen(GroupIndex.unsafe(i)).sample.get._1
      )
      val body = s"""{"addresses":${writeJs(newAddresses)}}"""

      Put(s"/miners/addresses", body) check { response =>
        val addresses = newAddresses.map(Address.fromBase58(_, networkType).get)
        minerProbe.expectMsg(Miner.UpdateAddresses(addresses))
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

  it should "call GET /miners/block-candidate" in new RestServerFixture {
    withServer {
      var block: Option[BlockTemplate] = Some(dummyBlockTemplate)
      val blockEntryTemplate           = RestServer.blockTempateToCandidate(dummyBlockTemplate)

      minerProbe.setAutoPilot(new TestActor.AutoPilot {
        def run(sender: ActorRef, msg: Any): TestActor.AutoPilot = {
          msg match {
            case Miner.GetBlockCandidate(_) =>
              sender ! Miner.BlockCandidate(block)
              block match {
                case Some(_) =>
                  block = None
                  TestActor.KeepRunning
                case None =>
                  TestActor.NoAutoPilot
              }
          }
        }
      })

      Get(s"/miners/block-candidate?fromGroup=1&toGroup=1") check { response =>
        response.code is StatusCode.Ok
        response.as[BlockCandidate] is blockEntryTemplate
      }

      //Miner return BlockCandidate(None)
      Get(s"/miners/block-candidate?fromGroup=1&toGroup=1") check { response =>
        response.code is StatusCode.InternalServerError
        response.as[ApiError.InternalServerError] is ApiError.InternalServerError(
          "Cannot compute block candidate for given chain index"
        )
      }

      interCliqueSynced = false

      Get(s"/miners/block-candidate?fromGroup=1&toGroup=1") check { response =>
        response.code is StatusCode.ServiceUnavailable
        response.as[ApiError.ServiceUnavailable] is ApiError.ServiceUnavailable(
          "The clique is not synced"
        )
      }
    }
  }

  it should "call POST /miners/new-block" in new RestServerFixture {
    withServer {

      val blockHash    = BlockHash.generate
      val depStateHash = Hash.generate
      val target       = Target.onePhPerBlock
      val ts           = TimeStamp.unsafe(1L)
      val txsHash      = Hash.generate

      val body =
        s"""{"blockDeps":["${blockHash.toHexString}"],"depStateHash":"${depStateHash.toHexString}","timestamp":${ts.millis},"fromGroup":1,"toGroup":1,"miningCount":"1","target":"${Hex
          .toHexString(
            target.bits
          )}","nonce":"1","txsHash":"${txsHash.toHexString}","transactions":[]}"""

      interCliqueSynced = false

      Post(s"/miners/new-block", body) check { response =>
        response.code is StatusCode.ServiceUnavailable
        response.as[ApiError.ServiceUnavailable] is ApiError.ServiceUnavailable(
          "The clique is not synced"
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

  trait RestServerFixture extends ServerFixture with HttpRouteFixture {
    lazy val minerProbe = TestProbe()
    lazy val miner      = ActorRefT[Miner.Command](minerProbe.ref)

    var selfCliqueSynced  = true
    var interCliqueSynced = true
    lazy val cliqueManager: ActorRefT[CliqueManager.Command] =
      ActorRefT.build(
        system,
        Props(new BaseActor {
          override def receive: Receive = {
            case CliqueManager.IsSelfCliqueReady =>
              sender() ! selfCliqueSynced
            case InterCliqueManager.IsSynced =>
              sender() ! InterCliqueManager.SyncedResult(interCliqueSynced)
          }
        }),
        s"clique-manager-${Random.nextInt()}"
      )

    lazy val blockFlowProbe = TestProbe()
    lazy val node = new NodeDummy(
      dummyIntraCliqueInfo,
      dummyNeighborPeers,
      dummyBlock,
      blockFlowProbe.ref,
      dummyTx,
      storages,
      cliqueManagerOpt = Some(cliqueManager)
    )
    lazy val blocksExporter = new BlocksExporter(node.blockFlow, rootPath)
    val walletConfig: WalletConfig = WalletConfig(
      None,
      (new java.io.File("")).toPath,
      NetworkType.Devnet,
      Duration.ofMinutesUnsafe(0),
      WalletConfig.BlockFlow("host", 0, 0, Duration.ofMinutesUnsafe(0))
    )

    lazy val port      = node.config.network.restPort
    lazy val walletApp = new WalletApp(walletConfig)
    lazy val server: RestServer =
      RestServer(node, miner, blocksExporter, Some(walletApp.walletServer))

    def withServer(f: => Any) = {
      try {
        server.start().futureValue
        f
      } finally {
        server.stop().futureValue
      }
    }
  }
}
