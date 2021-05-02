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

import scala.io.Source
import scala.util.Random

import akka.actor.{ActorRef, Props}
import akka.http.scaladsl.model._
import akka.http.scaladsl.testkit.ScalatestRouteTest
import akka.testkit.{TestActor, TestProbe}
import de.heikoseeberger.akkahttpupickle.UpickleCustomizationSupport
import org.scalatest.EitherValues

import org.alephium.api.{ApiError, ApiModel}
import org.alephium.api.UtilJson.avectorReadWriter
import org.alephium.api.model._
import org.alephium.app.ServerFixture.NodeDummy
import org.alephium.flow.client.Miner
import org.alephium.flow.model.BlockTemplate
import org.alephium.flow.network.{CliqueManager, InterCliqueManager}
import org.alephium.json.Json
import org.alephium.json.Json._
import org.alephium.protocol.{BlockHash, Hash}
import org.alephium.protocol.model.{Address, ChainIndex, GroupIndex, NetworkType, Target}
import org.alephium.serde.serialize
import org.alephium.util._
import org.alephium.wallet.WalletApp
import org.alephium.wallet.config.WalletConfig

class RestServerSpec
    extends AlephiumFutureSpec
    with ScalatestRouteTest
    with EitherValues
    with NumericHelpers
    with UpickleCustomizationSupport {

  override type Api = Json.type

  override def api: Api = Json

  it should "call GET /blockflow" in new RestServerFixture {
    Get(s"/blockflow?fromTs=0&toTs=0") ~> server.route ~> check {
      status is StatusCodes.OK
      responseAs[FetchResponse] is dummyFetchResponse
    }
    Get(s"/blockflow?fromTs=10&toTs=0}") ~> server.route ~> check {
      status is StatusCodes.BadRequest
      responseAs[ApiError.BadRequest] is ApiError.BadRequest(
        """Invalid value for: query parameter toTs (For input string: "0}": 0})"""
      )
    }
  }

  it should "call GET /blockflow/blocks/<hash>" in new RestServerFixture {
    Get(s"/blockflow/blocks/${dummyBlockHeader.hash.toHexString}") ~> server.route ~> check {
      val chainIndex = ChainIndex.from(dummyBlockHeader.hash)
      if (brokerConfig.contains(chainIndex.from) || brokerConfig.contains(chainIndex.to)) {
        status is StatusCodes.OK
        responseAs[BlockEntry] is dummyBlockEntry
      } else {
        status is StatusCodes.BadRequest
      }
    }
  }

  it should "call GET /addresses/<address>/balance" in new RestServerFixture {
    Get(s"/addresses/$dummyKeyAddress/balance") ~> server.route ~> check {
      status is StatusCodes.OK
      responseAs[Balance] is dummyBalance
    }
  }

  it should "call GET /addresses/<address>/group" in new RestServerFixture {
    Get(s"/addresses/$dummyKeyAddress/group") ~> server.route ~> check {
      status is StatusCodes.OK
      responseAs[Group] is dummyGroup
    }
  }

  it should "call GET /blockflow/hashes" in new RestServerFixture {
    Get(s"/blockflow/hashes?fromGroup=1&toGroup=1&height=1") ~> server.route ~> check {
      status is StatusCodes.OK
      responseAs[HashesAtHeight] is dummyHashesAtHeight
    }
    Get(s"/blockflow/hashes?toGroup=1&height=1") ~> server.route ~> check {
      status is StatusCodes.BadRequest
    }
    Get(s"/blockflow/hashes?fromGroup=1&height=1") ~> server.route ~> check {
      status is StatusCodes.BadRequest
    }
    Get(s"/blockflow/hashes?fromGroup=1&toGroup=1") ~> server.route ~> check {
      status is StatusCodes.BadRequest
    }
    Get(s"/blockflow/hashes?fromGroup=10&toGroup=1&height=1") ~> server.route ~> check {
      status is StatusCodes.BadRequest
    }
    Get(s"/blockflow/hashes?fromGroup=1&toGroup=10&height=1") ~> server.route ~> check {
      status is StatusCodes.BadRequest
    }
    Get(s"/blockflow/hashes?fromGroup=1&toGroup=10&height=-1") ~> server.route ~> check {
      status is StatusCodes.BadRequest
    }
  }

  it should "call GET /blockflow/chains" in new RestServerFixture {
    Get(s"/blockflow/chains?fromGroup=1&toGroup=1") ~> server.route ~> check {
      status is StatusCodes.OK
      responseAs[ChainInfo] is dummyChainInfo
    }
    Get(s"/blockflow/chains?toGroup=1") ~> server.route ~> check {
      status is StatusCodes.BadRequest
      responseAs[ApiError.BadRequest] is ApiError.BadRequest(
        s"Invalid value for: query parameter fromGroup"
      )
    }
    Get(s"/blockflow/chains?fromGroup=1") ~> server.route ~> check {
      status is StatusCodes.BadRequest
    }
    Get(s"/blockflow/chains?fromGroup=10&toGroup=1") ~> server.route ~> check {
      status is StatusCodes.BadRequest
    }
    Get(s"/blockflow/chains?fromGroup=1&toGroup=10") ~> server.route ~> check {
      status is StatusCodes.BadRequest
    }
  }

  it should "call GET /transactions/unconfirmed" in new RestServerFixture {
    Get(s"/transactions/unconfirmed?fromGroup=0&toGroup=0") ~> server.route ~> check {
      status is StatusCodes.OK
      responseAs[AVector[Tx]] is AVector.empty[Tx]
    }
  }

  it should "call GET /transactions/build" in new RestServerFixture {
    Get(
      s"/transactions/build?fromKey=$dummyKey&toAddress=$dummyToAddres&value=1"
    ) ~> server.route ~> check {
      status is StatusCodes.OK
      responseAs[BuildTransactionResult] is dummyBuildTransactionResult
    }
    Get(
      s"/transactions/build?fromKey=$dummyKey&toAddress=$dummyToAddres&lockTime=1234&value=1"
    ) ~> server.route ~> check {
      status is StatusCodes.OK
      responseAs[BuildTransactionResult] isnot dummyBuildTransactionResult
    }

    interCliqueSynced = false

    Get(
      s"/transactions/build?fromKey=$dummyKey&toAddress=$dummyToAddres&value=1"
    ) ~> server.route ~> check {
      status is StatusCodes.ServiceUnavailable
      responseAs[ApiError.ServiceUnavailable] is ApiError.ServiceUnavailable(
        "The clique is not synced"
      )
    }
  }

  it should "call POST /transactions/send" in new RestServerFixture {
    val tx =
      s"""{"unsignedTx":"${Hex.toHexString(
        serialize(dummyTx.unsigned)
      )}","signature":"${dummySignature.toHexString}","publicKey":"$dummyKey"}"""
    val entity = HttpEntity(ContentTypes.`application/json`, tx)
    Post(s"/transactions/send", entity) ~> server.route ~> check {
      status is StatusCodes.OK
      responseAs[TxResult] is dummyTransferResult
    }

    interCliqueSynced = false

    Post(s"/transactions/send", entity) ~> server.route ~> check {
      status is StatusCodes.ServiceUnavailable
      responseAs[ApiError.ServiceUnavailable] is ApiError.ServiceUnavailable(
        "The clique is not synced"
      )
    }
  }

  it should "call GET /transactions/status" in new RestServerFixture {
    Get(
      s"/transactions/status?txId=${Hash.zero.toHexString}&fromGroup=0&toGroup=1"
    ) ~> server.route ~> check {
      status is StatusCodes.OK
      responseAs[TxStatus] is dummyTxStatus
    }
  }

  it should "call POST /miners" in new RestServerFixture {
    Post(s"/miners?action=start-mining") ~> server.route ~> check {
      status is StatusCodes.OK
      responseAs[Boolean] is true
      minerProbe.expectMsg(Miner.Start)
    }

    Post(s"/miners?action=stop-mining") ~> server.route ~> check {
      status is StatusCodes.OK
      responseAs[Boolean] is true
      minerProbe.expectMsg(Miner.Stop)
    }

    interCliqueSynced = false

    Post(s"/miners?action=start-mining") ~> server.route ~> check {
      status is StatusCodes.ServiceUnavailable
      responseAs[ApiError.ServiceUnavailable] is ApiError.ServiceUnavailable(
        "The clique is not synced"
      )
    }
  }

  it should "call GET /miners/addresses" in new RestServerFixture {
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

    Get(s"/miners/addresses") ~> server.route ~> check {
      status is StatusCodes.OK
      responseAs[MinerAddresses] is MinerAddresses(AVector(address))
    }
  }

  it should "call PUT /miners/addresses" in new RestServerFixture {
    val newAddresses = AVector.tabulate(config.broker.groups)(i =>
      addressStringGen(GroupIndex.unsafe(i)).sample.get._1
    )
    val body   = s"""{"addresses":${writeJs(newAddresses)}}"""
    val entity = HttpEntity(ContentTypes.`application/json`, body)

    Put(s"/miners/addresses", entity) ~> server.route ~> check {
      val addresses = newAddresses.map(Address.fromBase58(_, networkType).get)
      minerProbe.expectMsg(Miner.UpdateAddresses(addresses))
      status is StatusCodes.OK
    }

    val notEnoughAddressesBody = s"""{"addresses":["${dummyKeyAddress}"]}"""
    val notEnoughAddressesEntity =
      HttpEntity(ContentTypes.`application/json`, notEnoughAddressesBody)
    Put(s"/miners/addresses", notEnoughAddressesEntity) ~> server.route ~> check {
      status is StatusCodes.BadRequest
      responseAs[ApiError.BadRequest] is ApiError.BadRequest(
        s"Wrong number of addresses, expected ${config.broker.groups}, got 1"
      )
    }

    val wrongGroup       = AVector.tabulate(config.broker.groups)(_ => dummyKeyAddress)
    val wrongGroupBody   = s"""{"addresses":${writeJs(wrongGroup)}}"""
    val wrongGroupEntity = HttpEntity(ContentTypes.`application/json`, wrongGroupBody)
    Put(s"/miners/addresses", wrongGroupEntity) ~> server.route ~> check {
      status is StatusCodes.BadRequest
      responseAs[ApiError.BadRequest] is ApiError.BadRequest(
        s"Address ${dummyKeyAddress} doesn't belong to group 1"
      )
    }
  }

  it should "call GET /miners/block-candidate" in new RestServerFixture {
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

    Get(s"/miners/block-candidate?fromGroup=1&toGroup=1") ~> server.route ~> check {
      status is StatusCodes.OK
      responseAs[BlockCandidate] is blockEntryTemplate
    }

    //Miner return BlockCandidate(None)
    Get(s"/miners/block-candidate?fromGroup=1&toGroup=1") ~> server.route ~> check {
      status is StatusCodes.InternalServerError
      responseAs[ApiError.InternalServerError] is ApiError.InternalServerError(
        "Cannot compute block candidate for given chain index"
      )
    }

    interCliqueSynced = false

    Get(s"/miners/block-candidate?fromGroup=1&toGroup=1") ~> server.route ~> check {
      status is StatusCodes.ServiceUnavailable
      responseAs[ApiError.ServiceUnavailable] is ApiError.ServiceUnavailable(
        "The clique is not synced"
      )
    }
  }

  it should "call POST /miners/new-block" in new RestServerFixture {

    val blockHash = BlockHash.generate
    val target    = Target.onePhPerBlock
    val ts        = TimeStamp.unsafe(1L)
    val txsHash   = Hash.generate

    val body =
      s"""{"blockDeps":["${blockHash.toHexString}"],"timestamp":${ts.millis},"fromGroup":1,"toGroup":1,"miningCount":"1","target":"${Hex
        .toHexString(
          target.bits
        )}","nonce":"1","txsHash":"${txsHash.toHexString}","transactions":[]}"""

    val entity = HttpEntity(ContentTypes.`application/json`, body)

    interCliqueSynced = false

    Post(s"/miners/new-block", entity) ~> server.route ~> check {
      status is StatusCodes.ServiceUnavailable
      responseAs[ApiError.ServiceUnavailable] is ApiError.ServiceUnavailable(
        "The clique is not synced"
      )
    }
  }

  it should "call GET /docs" in new RestServerFixture {
    Get(s"/docs") ~> server.route ~> check {
      status is StatusCodes.PermanentRedirect
    }

    Get(s"/docs/openapi.json") ~> server.route ~> check {
      status is StatusCodes.OK

      val timeout = Duration.ofMinutesUnsafe(1).asScala

      val openapiPath = ApiModel.getClass.getResource("/openapi.json")
      val expectedOpenapi =
        read[ujson.Value](
          Source.fromFile(openapiPath.getPath, "UTF-8").getLines().toSeq.mkString("\n")
        )

      val openapi =
        read[ujson.Value](response.entity.toStrict(timeout).futureValue.getData.utf8String)

      Get(s"/docs") ~> server.route ~> check {
        status is StatusCodes.PermanentRedirect
        openapi is expectedOpenapi
      }

    }
  }

  trait RestServerFixture extends ServerFixture {
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

    lazy val walletApp = new WalletApp(walletConfig)
    lazy val server: RestServer =
      RestServer(node, miner, blocksExporter, Some(walletApp.walletServer))
  }
}
