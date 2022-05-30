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
import akka.util.ByteString
import org.scalacheck.Gen

import org.alephium.api.ApiModelCodec
import org.alephium.api.model.{AssetOutput => _, ContractOutput => _, Transaction => _, _}
import org.alephium.crypto.Blake2b
import org.alephium.flow.client.Node
import org.alephium.flow.core._
import org.alephium.flow.core.BlockChain.TxIndex
import org.alephium.flow.core.FlowUtils.AssetOutputInfo
import org.alephium.flow.handler.{AllHandlers, TxHandler}
import org.alephium.flow.io.{Storages, StoragesFixture}
import org.alephium.flow.mempool.MemPool
import org.alephium.flow.network._
import org.alephium.flow.network.bootstrap.{InfoFixture, IntraCliqueInfo}
import org.alephium.flow.network.broker.MisbehaviorManager
import org.alephium.flow.setting.{AlephiumConfig, AlephiumConfigFixture}
import org.alephium.io.IOResult
import org.alephium.json.Json._
import org.alephium.protocol._
import org.alephium.protocol.model._
import org.alephium.protocol.model.ModelGenerators
import org.alephium.protocol.model.UnsignedTransaction.TxOutputInfo
import org.alephium.protocol.vm._
import org.alephium.util._
import org.alephium.util.Hex.HexStringSyntax

trait ServerFixture
    extends InfoFixture
    with ApiModelCodec
    with AlephiumConfigFixture
    with ModelGenerators
    with TxGenerators
    with StoragesFixture.Default
    with NoIndexModelGeneratorsLike {

  lazy val dummyBlockHeader =
    blockGen.sample.get.header.copy(timestamp = (TimeStamp.now() - Duration.ofMinutes(5).get).get)
  lazy val dummyBlock = blockGen.sample.get.copy(header = dummyBlockHeader)
  lazy val dummyFetchResponse = FetchResponse(
    AVector(AVector(BlockEntry.from(dummyBlock, 1)))
  )
  lazy val dummyIntraCliqueInfo = genIntraCliqueInfo
  lazy val dummySelfClique =
    EndpointsLogic.selfCliqueFrom(dummyIntraCliqueInfo, true, true)
  lazy val dummyBlockEntry      = BlockEntry.from(dummyBlock, 1)
  lazy val dummyNeighborPeers   = NeighborPeers(AVector.empty)
  lazy val dummyBalance         = Balance.from(Amount.Zero, Amount.Zero, 0)
  lazy val dummyGroup           = Group(Gen.choose(0, brokerConfig.groups - 1).sample.get)
  lazy val dummyContract        = counterContract
  lazy val dummyContractGroup   = Group(brokerConfig.groups - 1)
  lazy val dummyContractAddress = Address.Contract(LockupScript.P2C(counterContract.hash)).toBase58
  lazy val (dummyKeyAddress, dummyKey, dummyPrivateKey) = addressStringGen(
    GroupIndex.unsafe(dummyGroup.group)
  ).sample.get
  lazy val dummyKeyHex                     = dummyKey.toHexString
  lazy val (dummyToAddress, dummyToKey, _) = addressStringGen(GroupIndex.unsafe(1)).sample.get
  lazy val dummyToLockupScript             = LockupScript.p2pkh(dummyToKey)

  lazy val dummyHashesAtHeight = HashesAtHeight(AVector.empty)
  lazy val dummyChainInfo      = ChainInfo(0)

  lazy val dummyTx = transactionGen()
    .retryUntil(tx => tx.unsigned.inputs.nonEmpty && tx.unsigned.fixedOutputs.nonEmpty)
    .sample
    .get
  lazy val dummySignature =
    SignatureSchema.sign(
      dummyTx.unsigned.hash.bytes,
      PrivateKey.unsafe(Hex.unsafe(dummyPrivateKey.toHexString))
    )
  lazy val dummyTransferResult = TxResult(
    dummyTx.id,
    dummyTx.fromGroup.value,
    dummyTx.toGroup.value
  )
  def dummyBuildTransactionResult(tx: Transaction) = BuildTransactionResult.from(tx.unsigned)
  def dummySweepAddressBuildTransactionsResult(
      tx: Transaction,
      fromGroup: GroupIndex,
      toGroup: GroupIndex
  ) = BuildSweepAddressTransactionsResult.from(tx.unsigned, fromGroup, toGroup)
  lazy val dummyTxStatus: TxStatus = Confirmed(dummyBlock.hash, 0, 1, 2, 3)
}

object ServerFixture {
  def show[T: Writer](t: T): String = {
    write(t)
  }

  def dummyTransferTx(
      tx: Transaction,
      outputInfos: AVector[TxOutputInfo]
  ): Transaction = {
    val newOutputs = outputInfos.map {
      case TxOutputInfo(toLockupScript, amount, tokens, lockTimeOpt, _) =>
        TxOutput.asset(amount, toLockupScript, tokens, lockTimeOpt)
    }
    tx.copy(unsigned = tx.unsigned.copy(fixedOutputs = newOutputs))
  }

  def dummySweepAddressTx(
      tx: Transaction,
      toLockupScript: LockupScript.Asset,
      lockTimeOpt: Option[TimeStamp]
  ): Transaction = {
    val output = TxOutput.asset(
      U256.Ten,
      toLockupScript,
      AVector((Hash.hash("token1"), U256.One), (Hash.hash("token2"), U256.Two)),
      lockTimeOpt
    )
    tx.copy(
      unsigned = tx.unsigned.copy(fixedOutputs = AVector(output))
    )
  }

  def p2mpkhAddress(publicKeys: AVector[String], mrequired: Int): Address.Asset = {
    Address.Asset(
      LockupScript
        .p2mpkh(
          publicKeys.map { publicKey =>
            PublicKey.from(Hex.from(publicKey).get).get
          },
          mrequired
        )
        .get
    )
  }

  class DiscoveryServerDummy(neighborPeers: NeighborPeers) extends BaseActor {
    def receive: Receive = { case DiscoveryServer.GetNeighborPeers =>
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
      dummyContract: StatefulContract,
      storages: Storages,
      cliqueManagerOpt: Option[ActorRefT[CliqueManager.Command]] = None,
      misbehaviorManagerOpt: Option[ActorRefT[MisbehaviorManager.Command]] = None
  )(implicit val config: AlephiumConfig)
      extends Node {
    implicit val system: ActorSystem       = ActorSystem("NodeDummy")
    val executionContext: ExecutionContext = scala.concurrent.ExecutionContext.Implicits.global
    val blockFlow: BlockFlow =
      new BlockFlowDummy(block, blockFlowProbe, dummyTx, dummyContract, storages)

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

    val discoveryServerDummy = system.actorOf(Props(new DiscoveryServerDummy(neighborPeers)))
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

    val boostraperDummy = system.actorOf(Props(new BootstrapperDummy(intraCliqueInfo)))
    val bootstrapper: ActorRefT[Bootstrapper.Command] = ActorRefT(boostraperDummy)

    override protected def stopSelfOnce(): Future[Unit] = Future.successful(())
  }

  class BlockFlowDummy(
      block: Block,
      blockFlowProbe: ActorRef,
      dummyTx: Transaction,
      dummyContract: StatefulContract,
      val storages: Storages
  )(implicit val config: AlephiumConfig)
      extends EmptyBlockFlow {

    override def getHeightedBlocks(
        fromTs: TimeStamp,
        toTs: TimeStamp
    ): IOResult[AVector[(ChainIndex, AVector[(Block, Int)])]] = {
      blockFlowProbe ! (block.header.timestamp >= fromTs && block.header.timestamp <= toTs)
      Right(AVector((block.chainIndex, AVector((block, 1)))))
    }

    override def getBalance(
        lockupScript: LockupScript.Asset,
        utxosLimit: Int
    ): IOResult[(U256, U256, Int)] =
      Right((U256.Zero, U256.Zero, 0))

    override def getUTXOsIncludePool(
        lockupScript: LockupScript.Asset,
        utxosLimit: Int
    ): IOResult[AVector[AssetOutputInfo]] = {
      val assetOutputInfos = AVector(U256.One, U256.Two).map { amount =>
        val tokens = AVector((Hash.hash("token1"), U256.One))
        val output = AssetOutput(amount, lockupScript, TimeStamp.now(), tokens, ByteString.empty)
        val ref    = AssetOutputRef.unsafe(Hint.from(output), Hash.generate)
        AssetOutputInfo(ref, output, FlowUtils.PersistedOutput)
      }

      Right(assetOutputInfos)
    }

    override def transfer(
        fromPublicKey: PublicKey,
        outputInfos: AVector[TxOutputInfo],
        gasOpt: Option[GasBox],
        gasPrice: GasPrice,
        utxosLimit: Int
    ): IOResult[Either[String, UnsignedTransaction]] = {
      Right(Right(dummyTransferTx(dummyTx, outputInfos).unsigned))
    }

    override def transfer(
        fromLockupScript: LockupScript.Asset,
        fromUnlockScript: UnlockScript,
        outputInfos: AVector[TxOutputInfo],
        gasOpt: Option[GasBox],
        gasPrice: GasPrice,
        utxosLimit: Int
    ): IOResult[Either[String, UnsignedTransaction]] = {
      Right(Right(dummyTransferTx(dummyTx, outputInfos).unsigned))
    }

    override def sweepAddress(
        fromPublicKey: PublicKey,
        toLockupScript: LockupScript.Asset,
        lockTimeOpt: Option[TimeStamp],
        gasOpt: Option[GasBox],
        gasPrice: GasPrice,
        utxosLimit: Int
    ): IOResult[Either[String, AVector[UnsignedTransaction]]] = {
      Right(Right(AVector(dummySweepAddressTx(dummyTx, toLockupScript, lockTimeOpt).unsigned)))
    }

    // scalastyle:off no.equal
    val blockChainIndex = ChainIndex.from(block.hash, config.broker.groups)
    override def getTxStatus(
        txId: Hash,
        chainIndex: ChainIndex
    ): IOResult[Option[BlockFlowState.TxStatus]] = {
      assume(brokerConfig.contains(chainIndex.from))
      if (chainIndex == blockChainIndex) {
        Right(Some(BlockFlowState.Confirmed(TxIndex(block.hash, 0), 1, 2, 3)))
      } else {
        Right(None)
      }
    }
    // scalastyle:on no.equal

    override def getMemPool(mainGroup: GroupIndex): MemPool = {
      MemPool.empty(mainGroup)(config.broker, config.mempool)
    }

    override def getMemPool(chainIndex: ChainIndex): MemPool = {
      MemPool.empty(chainIndex.from)(config.broker, config.mempool)
    }

    override def getHeight(hash: BlockHash): IOResult[Int]              = Right(1)
    override def getBlockHeader(hash: BlockHash): IOResult[BlockHeader] = Right(block.header)
    override def getBlock(hash: BlockHash): IOResult[Block]             = Right(block)
    override def calWeight(block: Block): IOResult[Weight]              = ???

    override def getHeightedIntraBlocks(
        fromTs: TimeStamp,
        toTs: TimeStamp
    ): IOResult[AVector[(ChainIndex, AVector[(Block, Int)])]] = {
      Right(AVector((block.chainIndex, AVector((block, 10)))))
    }

    override def getGroupForContract(contractId: ContractId): Either[String, GroupIndex] = {
      Right(GroupIndex.unsafe(0))
    }

    override def searchLocalTransactionStatus(
        txId: Hash,
        chainIndexes: AVector[ChainIndex]
    ): Either[String, Option[BlockFlowState.TxStatus]] = {
      val blockChainIndex = ChainIndex.from(block.hash, config.broker.groups)
      if (brokerConfig.chainIndexes.contains(blockChainIndex)) {
        Right(
          Some(
            BlockFlowState.Confirmed(
              index = TxIndex(block.hash, 0),
              chainConfirmations = 1,
              fromGroupConfirmations = 2,
              toGroupConfirmations = 3
            )
          )
        )
      } else {
        Right(None)
      }
    }

    override def getEvents(
        eventKey: Hash,
        start: Int,
        end: Int
    ): IOResult[(Int, AVector[LogStates])] = {
      lazy val address1 = Address.fromBase58("16BCZkZzGb3QnycJQefDHqeZcTA5RhrwYUDsAYkCf7RhS").get
      lazy val address2 = Address.fromBase58("27gAhB8JB6UtE9tC3PwGRbXHiZJ9ApuCMoHqe1T4VzqFi").get

      val eventKeysWithoutEvents: Seq[Hash] = Seq(
        Blake2b.unsafe(hex"aab64e9c814749cea508857b23c7550da30b67216950c461ccac1a14a58661c3"),
        Blake2b.unsafe(hex"e939f9c5d2ad12ea2375dcc5231f5f25db0a2ac8af426f547819e13559aa693e")
      )
      val isBlackListed           = eventKeysWithoutEvents.contains(eventKey)
      val blockChainIndex         = ChainIndex.from(block.hash, config.broker.groups)
      val chainOnCurrentNode      = brokerConfig.chainIndexes.contains(blockChainIndex)
      val shouldReturnEmptyEvents = !chainOnCurrentNode || isBlackListed

      val logStates = LogStates(
        block.hash,
        eventKey,
        states = AVector(
          LogState(
            txId = dummyTx.id,
            index = 0,
            fields = AVector(
              vm.Val.U256(U256.unsafe(4)),
              vm.Val.Address(address1.lockupScript),
              vm.Val.Address(address2.lockupScript)
            )
          )
        )
      )

      if (shouldReturnEmptyEvents) {
        Right((0, AVector.empty))
      } else {
        Right((2, AVector(logStates)))
      }
    }

    override def getEventsCurrentCount(
        chainIndex: ChainIndex,
        eventKey: Hash
    ): IOResult[Option[Int]] = {
      Right(Some(10))
    }

    // scalastyle:off no.equal
    override def getBestCachedWorldState(groupIndex: GroupIndex): IOResult[WorldState.Cached] = {
      val contractGroup = brokerConfig.groups - 1
      if (
        brokerConfig.groupRange
          .contains(groupIndex.value) && brokerConfig.groupRange.contains(
          contractGroup
        ) && (groupIndex.value == contractGroup)
      ) {
        val contractId: Hash = dummyContract.toHalfDecoded().hash
        storages.emptyWorldState
          .createContractUnsafe(
            dummyContract.toHalfDecoded(),
            AVector(vm.Val.U256(U256.Zero)),
            ContractOutputRef.unsafe(Hint.unsafe(0), contractId),
            ContractOutput(U256.Zero, LockupScript.P2C(contractId), AVector())
          )
          .map(_.cached())
      } else {
        Right(storages.emptyWorldState.cached())
      }
    }
    // scalastyle:on no.equal

    override def getBestPersistedWorldState(
        groupIndex: GroupIndex
    ): IOResult[WorldState.Persisted] = getBestCachedWorldState(groupIndex).flatMap(_.persist())
  }
}
