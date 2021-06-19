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

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Random

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.testkit.TestProbe

import org.alephium.api.ApiModelCodec
import org.alephium.api.model._
import org.alephium.flow.client.Node
import org.alephium.flow.core._
import org.alephium.flow.core.BlockChain.TxIndex
import org.alephium.flow.handler.{AllHandlers, TxHandler}
import org.alephium.flow.io.{Storages, StoragesFixture}
import org.alephium.flow.network._
import org.alephium.flow.network.bootstrap.{InfoFixture, IntraCliqueInfo}
import org.alephium.flow.network.broker.MisbehaviorManager
import org.alephium.flow.setting.{AlephiumConfig, AlephiumConfigFixture}
import org.alephium.io.IOResult
import org.alephium.json.Json._
import org.alephium.protocol._
import org.alephium.protocol.model._
import org.alephium.protocol.vm.{GasBox, GasPrice, LockupScript}
import org.alephium.serde.serialize
import org.alephium.util._

trait ServerFixture
    extends InfoFixture
    with ApiModelCodec
    with AlephiumConfigFixture
    with StoragesFixture.Default
    with NoIndexModelGeneratorsLike {
  implicit lazy val apiConfig: ApiConfig     = ApiConfig.load(newConfig)
  implicit lazy val networkType: NetworkType = config.network.networkType

  val now = TimeStamp.now()

  lazy val blockflowFetchMaxAge = apiConfig.blockflowFetchMaxAge

  lazy val dummyBlockHeader =
    blockGen.sample.get.header.copy(timestamp = (now - Duration.ofMinutes(5).get).get)
  lazy val dummyBlock           = blockGen.sample.get.copy(header = dummyBlockHeader)
  lazy val dummyFetchResponse   = FetchResponse(AVector(BlockEntry.from(dummyBlockHeader, 1)))
  lazy val dummyIntraCliqueInfo = genIntraCliqueInfo
  lazy val dummySelfClique      = RestServer.selfCliqueFrom(dummyIntraCliqueInfo, config.consensus, true)
  lazy val dummyBlockEntry      = BlockEntry.from(dummyBlock, 1, networkType)
  lazy val dummyNeighborPeers   = NeighborPeers(AVector.empty)
  lazy val dummyBalance         = Balance(U256.Zero, U256.Zero, 0)
  lazy val dummyGroup           = Group(0)

  lazy val (dummyKeyAddress, dummyKey, dummyPrivateKey) = addressStringGen(
    GroupIndex.unsafe(0)
  ).sample.get
  lazy val (dummyToAddress, dummyToKey, _) = addressStringGen(GroupIndex.unsafe(1)).sample.get

  lazy val dummyHashesAtHeight = HashesAtHeight(AVector.empty)
  lazy val dummyChainInfo      = ChainInfo(0)
  lazy val dummyTx = transactionGen()
    .retryUntil(tx => tx.unsigned.inputs.nonEmpty && tx.unsigned.fixedOutputs.nonEmpty)
    .sample
    .get
  lazy val dummySignature =
    SignatureSchema.sign(
      dummyTx.unsigned.hash.bytes,
      PrivateKey.unsafe(Hex.unsafe(dummyPrivateKey))
    )
  lazy val dummyTransferResult = TxResult(
    dummyTx.id,
    dummyTx.fromGroup.value,
    dummyTx.toGroup.value
  )
  lazy val dummyBuildTransactionResult = BuildTransactionResult(
    Hex.toHexString(serialize(dummyTx.unsigned)),
    dummyTx.unsigned.hash,
    dummyTx.unsigned.fromGroup.value,
    dummyTx.unsigned.toGroup.value
  )
  lazy val dummyTxStatus: TxStatus = Confirmed(BlockHash.zero, 0, 1, 2, 3)
}

object ServerFixture {
  def show[T: Writer](t: T): String = {
    write(t)
  }

  class DiscoveryServerDummy(neighborPeers: NeighborPeers) extends BaseActor {
    def receive: Receive = { case DiscoveryServer.GetNeighborPeers(_) =>
      sender() ! DiscoveryServer.NeighborPeers(neighborPeers.peers)
    }
  }

  class BootstrapperDummy(intraCliqueInfo: IntraCliqueInfo) extends BaseActor {
    def receive: Receive = { case Bootstrapper.GetIntraCliqueInfo =>
      sender() ! intraCliqueInfo
    }
  }

  class NodeDummy(
      intraCliqueInfo: IntraCliqueInfo,
      neighborPeers: NeighborPeers,
      block: Block,
      blockFlowProbe: ActorRef,
      _allHandlers: AllHandlers,
      dummyTx: Transaction,
      storages: Storages,
      cliqueManagerOpt: Option[ActorRefT[CliqueManager.Command]] = None,
      misbehaviorManagerOpt: Option[ActorRefT[MisbehaviorManager.Command]] = None
  )(implicit val config: AlephiumConfig)
      extends Node {
    implicit val system: ActorSystem       = ActorSystem("NodeDummy")
    val executionContext: ExecutionContext = scala.concurrent.ExecutionContext.Implicits.global
    val blockFlow: BlockFlow               = new BlockFlowDummy(block, blockFlowProbe, dummyTx, storages)

    val misbehaviorManager: ActorRefT[MisbehaviorManager.Command] =
      misbehaviorManagerOpt.getOrElse(ActorRefT(TestProbe().ref))
    val tcpController: ActorRefT[TcpController.Command] = ActorRefT(TestProbe().ref)

    val eventBus =
      ActorRefT
        .build[EventBus.Message](
          system,
          EventBus.props(),
          s"EventBus-${Random.nextInt()}"
        )

    val discoveryServerDummy                                = system.actorOf(Props(new DiscoveryServerDummy(neighborPeers)))
    val discoveryServer: ActorRefT[DiscoveryServer.Command] = ActorRefT(discoveryServerDummy)

    val selfCliqueSynced  = true
    val interCliqueSynced = true
    val cliqueManager: ActorRefT[CliqueManager.Command] = cliqueManagerOpt.getOrElse {
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
        "clique-manager"
      )
    }

    val txHandlerRef =
      system.actorOf(AlephiumTestActors.const(TxHandler.AddSucceeded(dummyTx.id)))
    val txHandler   = ActorRefT[TxHandler.Command](txHandlerRef)
    val allHandlers = _allHandlers.copy(txHandler = txHandler)(config.broker)

    val boostraperDummy                               = system.actorOf(Props(new BootstrapperDummy(intraCliqueInfo)))
    val bootstrapper: ActorRefT[Bootstrapper.Command] = ActorRefT(boostraperDummy)

    override protected def stopSelfOnce(): Future[Unit] = Future.successful(())
  }

  class BlockFlowDummy(
      block: Block,
      blockFlowProbe: ActorRef,
      dummyTx: Transaction,
      val storages: Storages
  )(implicit val config: AlephiumConfig)
      extends EmptyBlockFlow {

    override def getHeightedBlockHeaders(
        fromTs: TimeStamp,
        toTs: TimeStamp
    ): IOResult[AVector[(BlockHeader, Int)]] = {
      blockFlowProbe ! (block.header.timestamp >= fromTs && block.header.timestamp <= toTs)
      Right(AVector((block.header, 1)))
    }

    override def getBalance(lockupScript: LockupScript): IOResult[(U256, U256, Int)] =
      Right((U256.Zero, U256.Zero, 0))

    override def transfer(
        fromKey: PublicKey,
        toLockupScript: LockupScript,
        lockTimeOpt: Option[TimeStamp],
        amount: U256,
        gasOpt: Option[GasBox],
        gasPrice: GasPrice
    ): IOResult[Either[String, UnsignedTransaction]] =
      lockTimeOpt match {
        case None => Right(Right(dummyTx.unsigned))
        case Some(lockTime) =>
          val outputs    = dummyTx.unsigned.fixedOutputs
          val newOutputs = outputs.map(_.copy(lockTime = lockTime))
          Right(Right(dummyTx.unsigned.copy(fixedOutputs = newOutputs)))
      }

    override def getTxStatus(
        txId: Hash,
        chainIndex: ChainIndex
    ): IOResult[Option[BlockFlowState.TxStatus]] =
      Right(Some(BlockFlowState.TxStatus(TxIndex(BlockHash.zero, 0), 1, 2, 3)))

    override def getHeight(hash: BlockHash): IOResult[Int]              = Right(1)
    override def getBlockHeader(hash: BlockHash): IOResult[BlockHeader] = Right(block.header)
    override def getBlock(hash: BlockHash): IOResult[Block]             = Right(block)
  }
}
