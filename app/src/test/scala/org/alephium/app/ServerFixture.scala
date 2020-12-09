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

import scala.concurrent.Future

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.testkit.TestProbe
import io.circe.Encoder
import io.circe.syntax._

import org.alephium.api.ApiModelCodec
import org.alephium.api.CirceUtils
import org.alephium.api.model._
import org.alephium.flow.client.Node
import org.alephium.flow.core._
import org.alephium.flow.handler.{AllHandlers, TxHandler}
import org.alephium.flow.io.{Storages, StoragesFixture}
import org.alephium.flow.model.BlockDeps
import org.alephium.flow.network.{Bootstrapper, CliqueManager, DiscoveryServer, TcpController}
import org.alephium.flow.network.bootstrap.{InfoFixture, IntraCliqueInfo}
import org.alephium.flow.network.broker.BrokerManager
import org.alephium.flow.setting.{AlephiumConfig, AlephiumConfigFixture}
import org.alephium.io.IOResult
import org.alephium.protocol.{BlockHash, PrivateKey, SignatureSchema}
import org.alephium.protocol.model._
import org.alephium.protocol.vm.{LockupScript, UnlockScript}
import org.alephium.serde.serialize
import org.alephium.util._

trait ServerFixture
    extends InfoFixture
    with ApiModelCodec
    with AlephiumConfigFixture
    with StoragesFixture
    with NoIndexModelGeneratorsLike {
  implicit lazy val apiConfig: ApiConfig     = ApiConfig.load(newConfig).toOption.get
  implicit lazy val networkType: NetworkType = config.network.networkType

  val now = TimeStamp.now()

  lazy val blockflowFetchMaxAge = apiConfig.blockflowFetchMaxAge

  lazy val dummyBlockHeader =
    blockGen.sample.get.header.copy(timestamp = (now - Duration.ofMinutes(5).get).get)
  lazy val dummyBlock           = blockGen.sample.get.copy(header = dummyBlockHeader)
  lazy val dummyFetchResponse   = FetchResponse(Seq(BlockEntry.from(dummyBlockHeader, 1)))
  lazy val dummyIntraCliqueInfo = genIntraCliqueInfo
  lazy val dummySelfClique      = RestServer.selfCliqueFrom(dummyIntraCliqueInfo)
  lazy val dummyBlockEntry      = BlockEntry.from(dummyBlock, 1, networkType)
  lazy val dummyNeighborCliques = NeighborCliques(AVector.empty)
  lazy val dummyBalance         = Balance(U256.Zero, 0)
  lazy val dummyGroup           = Group(0)

  lazy val (dummyKeyAddress, dummyKey, dummyPrivateKey) = addressStringGen(GroupIndex.unsafe(0)).sample.get
  lazy val (dummyToAddres, dummyToKey, _)               = addressStringGen(GroupIndex.unsafe(1)).sample.get

  lazy val dummyHashesAtHeight = HashesAtHeight(Seq.empty)
  lazy val dummyChainInfo      = ChainInfo(0)
  lazy val dummyTx = transactionGen()
    .retryUntil(tx => tx.unsigned.inputs.nonEmpty && tx.unsigned.fixedOutputs.nonEmpty)
    .sample
    .get
  lazy val dummySignature =
    SignatureSchema.sign(dummyTx.unsigned.hash.bytes,
                         PrivateKey.unsafe(Hex.unsafe(dummyPrivateKey)))
  lazy val dummyTransferResult = TxResult(
    dummyTx.hash.toHexString,
    dummyTx.fromGroup.value,
    dummyTx.toGroup.value
  )
  lazy val dummyBuildTransactionResult = BuildTransactionResult(
    Hex.toHexString(serialize(dummyTx.unsigned)),
    dummyTx.unsigned.hash.toHexString,
    dummyTx.unsigned.fromGroup.value,
    dummyTx.unsigned.toGroup.value
  )
}

object ServerFixture {
  def show[T](t: T)(implicit encoder: Encoder[T]): String = {
    CirceUtils.print(t.asJson)
  }

  class DiscoveryServerDummy(neighborCliques: NeighborCliques) extends BaseActor {
    def receive: Receive = {
      case DiscoveryServer.GetNeighborCliques =>
        sender() ! DiscoveryServer.NeighborCliques(neighborCliques.cliques)
    }
  }

  class BootstrapperDummy(intraCliqueInfo: IntraCliqueInfo) extends BaseActor {
    def receive: Receive = {
      case Bootstrapper.GetIntraCliqueInfo => sender() ! intraCliqueInfo
    }
  }

  class NodeDummy(intraCliqueInfo: IntraCliqueInfo,
                  neighborCliques: NeighborCliques,
                  block: Block,
                  blockFlowProbe: ActorRef,
                  dummyTx: Transaction,
                  storages: Storages)(implicit val config: AlephiumConfig)
      extends Node {
    implicit val system: ActorSystem = ActorSystem("NodeDummy")
    val blockFlow: BlockFlow         = new BlockFlowDummy(block, blockFlowProbe, dummyTx, storages)

    val brokerManager: ActorRefT[BrokerManager.Command] = ActorRefT(TestProbe().ref)
    val tcpController: ActorRefT[TcpController.Command] = ActorRefT(TestProbe().ref)

    val eventBus =
      ActorRefT
        .build[EventBus.Message](system, EventBus.props(), s"EventBus-${Random.source.nextInt}")

    val discoveryServerDummy                                = system.actorOf(Props(new DiscoveryServerDummy(neighborCliques)))
    val discoveryServer: ActorRefT[DiscoveryServer.Command] = ActorRefT(discoveryServerDummy)

    val selfCliqueSynced = true
    val cliqueManager: ActorRefT[CliqueManager.Command] =
      ActorRefT.build(system, Props(new BaseActor {
        override def receive: Receive = {
          case CliqueManager.IsSelfCliqueReady => sender() ! selfCliqueSynced
        }
      }), "clique-manager")

    val txHandlerRef =
      system.actorOf(AlephiumTestActors.const(TxHandler.AddSucceeded(dummyTx.hash)))
    val txHandler = ActorRefT[TxHandler.Command](txHandlerRef)

    val allHandlers: AllHandlers = AllHandlers(flowHandler = ActorRefT(TestProbe().ref),
                                               txHandler      = txHandler,
                                               blockHandlers  = Map.empty,
                                               headerHandlers = Map.empty)(config.broker)

    val boostraperDummy                               = system.actorOf(Props(new BootstrapperDummy(intraCliqueInfo)))
    val bootstrapper: ActorRefT[Bootstrapper.Command] = ActorRefT(boostraperDummy)

    val monitorProbe                     = TestProbe()
    val monitor: ActorRefT[Node.Command] = ActorRefT(monitorProbe.ref)

    override protected def stopSelfOnce(): Future[Unit] = Future.successful(())
  }

  class BlockFlowDummy(block: Block,
                       blockFlowProbe: ActorRef,
                       dummyTx: Transaction,
                       storages: Storages)(implicit val config: AlephiumConfig)
      extends BlockFlow {
    override def genesisBlocks: AVector[AVector[Block]] = config.genesisBlocks

    override def getHeightedBlockHeaders(fromTs: TimeStamp,
                                         toTs: TimeStamp): IOResult[AVector[(BlockHeader, Int)]] = {
      blockFlowProbe ! (block.header.timestamp >= fromTs && block.header.timestamp <= toTs)
      Right(AVector((block.header, 1)))
    }

    override def getBalance(lockupScript: LockupScript): IOResult[(U256, Int)] =
      Right((U256.Zero, 0))

    override def prepareUnsignedTx(fromLockupScript: LockupScript,
                                   fromUnlockScript: UnlockScript,
                                   toLockupScript: LockupScript,
                                   value: U256): IOResult[Option[UnsignedTransaction]] =
      Right(Some(dummyTx.unsigned))

    implicit def brokerConfig    = config.broker
    implicit def consensusConfig = config.consensus
    implicit def mempoolSetting  = config.mempool
    def blockchainWithStateBuilder: (Block, BlockFlow.WorldStateUpdater) => BlockChainWithState =
      BlockChainWithState.fromGenesisUnsafe(storages)
    def blockchainBuilder: Block => BlockChain =
      BlockChain.fromGenesisUnsafe(storages)
    def blockheaderChainBuilder: BlockHeader => BlockHeaderChain =
      BlockHeaderChain.fromGenesisUnsafe(storages)

    override def getHeight(hash: BlockHash): IOResult[Int]              = Right(1)
    override def getBlockHeader(hash: BlockHash): IOResult[BlockHeader] = Right(block.header)
    override def getBlock(hash: BlockHash): IOResult[Block]             = Right(block)

    def calBestDepsUnsafe(group: GroupIndex): BlockDeps = ???
    def getAllTips: AVector[BlockHash]                  = ???
    def getBestTipUnsafe: BlockHash                     = ???
    def add(header: org.alephium.protocol.model.BlockHeader,
            parentHash: BlockHash,
            weight: Int): IOResult[Unit]         = ???
    def updateBestDepsUnsafe(): Unit             = ???
    def updateBestDeps(): IOResult[Unit]         = ???
    def add(block: Block): IOResult[Unit]        = ???
    def add(header: BlockHeader): IOResult[Unit] = ???
  }
}
