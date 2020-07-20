package org.alephium.appserver

import scala.concurrent.{ExecutionContext, Future}

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.testkit.TestProbe
import io.circe.Encoder
import io.circe.syntax._

import org.alephium.appserver.ApiModel._
import org.alephium.crypto.{ED25519, ED25519PrivateKey}
import org.alephium.flow.client.Node
import org.alephium.flow.core._
import org.alephium.flow.io.{Storages, StoragesFixture}
import org.alephium.flow.model.{BlockDeps, SyncInfo}
import org.alephium.flow.network.{Bootstrapper, CliqueManager, DiscoveryServer, TcpServer}
import org.alephium.flow.network.bootstrap.{InfoFixture, IntraCliqueInfo}
import org.alephium.flow.platform.{Mode, PlatformConfig, PlatformConfigFixture}
import org.alephium.io.IOResult
import org.alephium.protocol.ALF.Hash
import org.alephium.protocol.model._
import org.alephium.protocol.vm.{LockupScript, UnlockScript}
import org.alephium.rpc.CirceUtils
import org.alephium.serde.serialize
import org.alephium.util._

trait ServerFixture
    extends InfoFixture
    with PlatformConfigFixture
    with StoragesFixture
    with NoIndexModelGeneratorsLike {

  val now = TimeStamp.now()

  val apiKey = ApiKey("1c8aff950685c2ed4bc3174f3472287b56d9517b9c948127319a09a7a36deac8")

  lazy val dummyBlockHeader =
    blockGen.sample.get.header.copy(timestamp = (now - Duration.ofMinutes(5).get).get)
  lazy val dummyBlock           = blockGen.sample.get.copy(header = dummyBlockHeader)
  lazy val dummyFetchResponse   = FetchResponse(Seq(BlockEntry.from(dummyBlockHeader, 1)))
  lazy val dummyIntraCliqueInfo = genIntraCliqueInfo(config)
  lazy val dummySelfClique      = SelfClique.from(dummyIntraCliqueInfo)
  lazy val dummyBlockEntry      = BlockEntry.from(dummyBlock, 1)
  lazy val dummyNeighborCliques = NeighborCliques(AVector.empty)
  lazy val dummyBalance         = Balance(U64.Zero, 0)
  lazy val dummyGroup           = Group(0)
  lazy val dummyKey             = "b4628b5e93e6356b8b2ce75174a57fbd2fe6907e6244d5ddfba78a94ebf9d7a5"
  lazy val dummyKeyAddress      = "1EvRjvdiVH24YUgjfCA7RZVTWj4bKexks9iY33YbL14zt"
  lazy val dummyToKey           = "3ec4489e0988fbe5ea1becd0804335cd78ae285883f4028009b0e69d0574cda9"
  lazy val dummyToAddres        = "19kCCFBGJV76XjyzszeMGTbnDVkAYwhHtZGKEvc1JdJEG"
  lazy val dummyPrivateKey      = "e89743f47eaef4d438b503e66de08f4eedd0d5d8c6ad9b9ff0177f081917ae1a"
  lazy val dummyHashesAtHeight  = HashesAtHeight(Seq.empty)
  lazy val dummyChainInfo       = ChainInfo(0)
  lazy val dummyTx = transactionGen
    .retryUntil(tx => tx.unsigned.inputs.nonEmpty && tx.unsigned.fixedOutputs.nonEmpty)
    .sample
    .get
  lazy val dummySignature =
    ED25519.sign(dummyTx.unsigned.hash.bytes, ED25519PrivateKey.unsafe(Hex.unsafe(dummyPrivateKey)))
  lazy val dummyTransferResult = TxResult(
    dummyTx.hash.toHexString,
    dummyTx.fromGroup.value,
    dummyTx.toGroup.value
  )
  lazy val dummyCreateTransactionResult = CreateTransactionResult(
    Hex.toHexString(serialize(dummyTx.unsigned)),
    dummyTx.unsigned.hash.toHexString
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
                  storages: Storages)(implicit val config: PlatformConfig)
      extends Node {
    implicit val system: ActorSystem = ActorSystem("NodeDummy")
    val blockFlow: BlockFlow         = new BlockFlowDummy(block, blockFlowProbe, dummyTx, storages)

    val serverProbe                          = TestProbe()
    val server: ActorRefT[TcpServer.Command] = ActorRefT(serverProbe.ref)

    val eventBus =
      ActorRefT
        .build[EventBus.Message](system, EventBus.props(), s"EventBus-${Random.source.nextInt}")

    val discoveryServerDummy                                = system.actorOf(Props(new DiscoveryServerDummy(neighborCliques)))
    val discoveryServer: ActorRefT[DiscoveryServer.Command] = ActorRefT(discoveryServerDummy)

    val selfCliqueSynced = true
    val cliqueManager: ActorRefT[CliqueManager.Command] =
      ActorRefT.build(system, Props(new BaseActor {
        override def receive: Receive = {
          case CliqueManager.IsSelfCliqueSynced => sender() ! selfCliqueSynced
        }
      }), "clique-manager")

    val txHandlerRef =
      system.actorOf(AlephiumTestActors.const(TxHandler.AddSucceeded(dummyTx.hash)))
    val txHandler = ActorRefT[TxHandler.Command](txHandlerRef)

    val allHandlers: AllHandlers = AllHandlers(flowHandler = ActorRefT(TestProbe().ref),
                                               txHandler      = txHandler,
                                               blockHandlers  = Map.empty,
                                               headerHandlers = Map.empty)

    val boostraperDummy                             = system.actorOf(Props(new BootstrapperDummy(intraCliqueInfo)))
    val boostraper: ActorRefT[Bootstrapper.Command] = ActorRefT(boostraperDummy)

    val monitorProbe                     = TestProbe()
    val monitor: ActorRefT[Node.Command] = ActorRefT(monitorProbe.ref)

    override protected def stopSelfOnce(): Future[Unit] = Future.successful(())
  }

  class BlockFlowDummy(block: Block,
                       blockFlowProbe: ActorRef,
                       dummyTx: Transaction,
                       storages: Storages)(implicit val config: PlatformConfig)
      extends BlockFlow {
    override def getHeightedBlockHeaders(fromTs: TimeStamp,
                                         toTs: TimeStamp): IOResult[AVector[(BlockHeader, Int)]] = {
      blockFlowProbe ! (block.header.timestamp >= fromTs && block.header.timestamp <= toTs)
      Right(AVector((block.header, 1)))
    }

    override def getBalance(address: Address): IOResult[(U64, Int)] = Right((U64.Zero, 0))

    override def prepareUnsignedTx(fromLockupScript: LockupScript,
                                   fromUnlockScript: UnlockScript,
                                   toLockupScript: LockupScript,
                                   value: U64): IOResult[Option[UnsignedTransaction]] =
      Right(Some(dummyTx.unsigned))

    override def prepareTx(fromLockupScript: LockupScript,
                           fromUnlockScript: UnlockScript,
                           toLockupScript: LockupScript,
                           value: U64,
                           fromPrivateKey: ED25519PrivateKey): IOResult[Option[Transaction]] = {
      Right(Some(dummyTx))
    }

    def blockchainWithStateBuilder: (ChainIndex, BlockFlow.TrieUpdater) => BlockChainWithState =
      BlockChainWithState.fromGenesisUnsafe(storages)
    def blockchainBuilder: ChainIndex => BlockChain =
      BlockChain.fromGenesisUnsafe(storages)
    def blockheaderChainBuilder: ChainIndex => BlockHeaderChain =
      BlockHeaderChain.fromGenesisUnsafe(storages)

    override def getHeight(hash: Hash): IOResult[Int]              = Right(1)
    override def getBlockHeader(hash: Hash): IOResult[BlockHeader] = Right(block.header)
    override def getBlock(hash: Hash): IOResult[Block]             = Right(block)

    def getInterCliqueSyncInfo(brokerInfo: BrokerInfo): SyncInfo   = ???
    def getIntraCliqueSyncInfo(remoteBroker: BrokerInfo): SyncInfo = ???
    def calBestDepsUnsafe(group: GroupIndex): BlockDeps            = ???
    def getAllTips: AVector[Hash]                                  = ???
    def getBestTipUnsafe: Hash                                     = ???
    def add(header: org.alephium.protocol.model.BlockHeader,
            parentHash: Hash,
            weight: Int): IOResult[Unit]         = ???
    def updateBestDepsUnsafe(): Unit             = ???
    def updateBestDeps(): IOResult[Unit]         = ???
    def add(block: Block): IOResult[Unit]        = ???
    def add(header: BlockHeader): IOResult[Unit] = ???
  }

  class ModeDummy(intraCliqueInfo: IntraCliqueInfo,
                  neighborCliques: NeighborCliques,
                  block: Block,
                  blockFlowProbe: ActorRef,
                  dummyTx: Transaction,
                  storages: Storages)(implicit val system: ActorSystem,
                                      val config: PlatformConfig,
                                      val executionContext: ExecutionContext)
      extends Mode {
    lazy val node =
      new NodeDummy(intraCliqueInfo, neighborCliques, block, blockFlowProbe, dummyTx, storages)

    override protected def stopSelfOnce(): Future[Unit] = Future.successful(())
  }
}
