package org.alephium.appserver

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Random

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.ws.TextMessage
import akka.http.scaladsl.server.{MethodRejection, UnsupportedRequestContentTypeRejection}
import akka.http.scaladsl.testkit.{ScalatestRouteTest, WSProbe}
import akka.stream.testkit.scaladsl.TestSink
import akka.testkit.TestProbe
import akka.util.Timeout
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._
import io.circe.{Decoder, Encoder, Json}
import io.circe.parser._
import io.circe.syntax._
import org.scalatest.{Assertion, EitherValues}
import org.scalatest.concurrent.ScalaFutures

import org.alephium.appserver.ApiModel._
import org.alephium.crypto.{ED25519, ED25519PrivateKey}
import org.alephium.flow.U64Helpers
import org.alephium.flow.client.{Miner, Node}
import org.alephium.flow.core._
import org.alephium.flow.core.FlowHandler.BlockNotify
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
import org.alephium.rpc.model.JsonRPC._
import org.alephium.serde.serialize
import org.alephium.util._

class RPCServerSpec
    extends AlephiumSpec
    with ScalatestRouteTest
    with EitherValues
    with ScalaFutures
    with U64Helpers {
  import RPCServerSpec._

  behavior of "http"

  it should "encode BlockNotify" in new Fixture {
    val header =
      BlockHeader(AVector(Hash.hash("foo")), Hash.hash("bar"), TimeStamp.zero, 1, 2)
    val blockNotify = BlockNotify(header, 1)

    val result = RPCServer.blockNotifyEncode(blockNotify)

    show(result) is """{"hash":"9d131ee1587f668cebefa70937dd8a1b374463131b23411d9cf02d8462c38e6d","timestamp":0,"chainFrom":0,"chainTo":2,"height":1,"deps":["41b1a0649752af1b28b3dc29a1556eee781e4a4c3a1f7f53f90fa834de098c4d"]}"""
  }

  it should "call mining_start" in new RouteHTTP {
    checkCallResult("mining_start")(true)
    minerProbe.expectMsg(Miner.Start)
  }

  it should "call mining_stop" in new RouteHTTP {
    checkCallResult("mining_stop")(true)
    minerProbe.expectMsg(Miner.Stop)
  }

  it should "call self_clique_synced" in new RouteHTTP {
    checkCallResult("self_clique_synced")(true)
  }

  it should "call blockflow_fetch" in new RouteHTTP {
    checkCallResult("blockflow_fetch", parse("""{"fromTs":1, "toTs":42}""").toOption)(
      dummyFetchResponse)
  }

  it should "call neighbor_cliques" in new RouteHTTP {
    checkCallResult("neighbor_cliques")(dummyNeighborCliques)
  }

  it should "call self_clique" in new RouteHTTP {
    checkCallResult("self_clique")(dummySelfClique)
  }

  it should "call get_balance" in new RouteHTTP {
    checkCallResult("get_balance", parse(s"""{"address":"$dummyKeyAddress"}""").toOption)(
      dummyBalance)
  }

  it should "reject wrong get_balance call" in new RouteHTTP {
    checkFailCallResult("get_balance", parse(s"""{"address":"OOPS"}""").toOption)("Invalid params")
    checkFailCallResult(
      "get_balance",
      parse(s"""{"address":"$dummyKey","type":"OOPS"}""").toOption)("Invalid params")
    checkFailCallResult("get_balance", parse(s"""{"OOPS":"OOPS"}""").toOption)("Invalid params")
  }

  it should "call get_group" in new RouteHTTP {
    checkCallResult("get_group", parse(s"""{"address":"$dummyKeyAddress"}""").toOption)(dummyGroup)
  }

  it should "call get_hashes_at_height" in new RouteHTTP {
    checkCallResult(
      "get_hashes_at_height",
      parse(s"""{"fromGroup":"1","toGroup":"1","height":1}""").toOption)(dummyHashesAtHeight)
  }

  it should "call get_chain_info" in new RouteHTTP {
    checkCallResult("get_chain_info", parse(s"""{"fromGroup":"1","toGroup":"1"}""").toOption)(
      dummyChainInfo)
  }

  it should "call get_block" in new RouteHTTP {
    val chainIndex = ChainIndex.from(dummyBlockHeader.hash)
    if (config.brokerInfo.contains(chainIndex.from) || config.brokerInfo.contains(chainIndex.to)) {
      checkCallResult(
        "get_block",
        parse(s"""{"hash":"${dummyBlockHeader.hash.toHexString}"}""").toOption)(dummyBlockEntry)
    } else {
      checkFailCallResult(
        "get_block",
        parse(s"""{"hash":"${dummyBlockHeader.hash.toHexString}"}""").toOption)("Server error")
    }
  }

  it should "call send_transaction" in new RouteHTTP {
    checkCallResult(
      "send_transaction",
      parse(s"""{"tx":"${Hex
        .toHexString(serialize(dummyTx.unsigned))}","signature":"${dummySignature.toHexString}","publicKey":"$dummyKey"}""").toOption
    )(dummyTransferResult)
  }

  it should "call create_transaction" in new RouteHTTP {
    checkCallResult(
      "create_transaction",
      parse(s"""{"fromKey":"$dummyKey","toAddress":"$dummyToAddres","value":1}""").toOption
    )(dummyCreateTransactionResult)
  }

  it should "reject when address belongs to other groups" in new RouteHTTP {
    override val configValues = Map(
      ("alephium.broker.brokerId", 1)
    )
    checkFailCallResult("get_balance", parse(s"""{"address":"$dummyKeyAddress"}""").toOption)(
      "Server error")
  }
  it should "reject GET" in new RouteHTTP {
    Get() ~> server.httpRoute ~> check {
      rejections is List(
        MethodRejection(HttpMethods.POST)
      )
    }
  }

  it should "reject wrong content type" in new RouteHTTP {
    val entity  = HttpEntity(ContentTypes.`text/plain(UTF-8)`, CirceUtils.print(Json.Null))
    val request = HttpRequest(HttpMethods.POST, entity = entity)

    request ~> server.httpRoute ~> check {
      val List(rejection: UnsupportedRequestContentTypeRejection) = rejections
      rejection.supported is Set(ContentTypeRange(ContentTypes.`application/json`))
    }
  }

  it should "run/stop the server" in new RouteHTTP {
    server.runServer().futureValue is (())
    server.stop().futureValue is (())
  }

  it should "make sure rps and ws port are provided" in new Fixture {
    override val configValues = Map(
      ("alephium.network.rpcPort", 0),
      ("alephium.network.wsPort", 0)
    )

    val mode: Mode = new ModeDummy(dummyIntraCliqueInfo,
                                   dummyNeighborCliques,
                                   dummyBlock,
                                   blockFlowProbe.ref,
                                   dummyTx,
                                   storages)

    assertThrows[RuntimeException] {
      RPCServer(mode, ActorRefT(TestProbe().ref))
    }
  }

  behavior of "companion object"

  it should "safely handle `blockflowFetch` function" in new Fixture {
    val dummyAddress = ModelGen.socketAddress.sample.get

    val blockflowFetchMaxAge = Duration.ofMinutes(10).get
    implicit val rpcConfig: RPCConfig =
      RPCConfig(dummyAddress.getAddress, blockflowFetchMaxAge, askTimeout = Duration.zero)
    implicit val fetchRequestDecoder: Decoder[FetchRequest] = FetchRequest.decoder

    val blockFlow = new BlockFlowDummy(dummyBlock, blockFlowProbe.ref, dummyTx, storages)
    def blockflowFetch(params: String) = {
      RPCServer.blockflowFetch(blockFlow, Request("blockflow_fetch", parse(params).toOption.get, 0))
    }
    val invalidParams = Response.Failure(Error.InvalidParams, Some(0L))

    blockflowFetch(
      s"""{"fromTs":${(now - blockflowFetchMaxAge).get.millis},"toTs":${now.millis}}""")
    blockFlowProbe.expectMsg(true)

    blockflowFetch(s"""{"fromTs":${now.millis},"toTs":${now.millis}}""")
    blockFlowProbe.expectMsg(false)

    blockflowFetch("""{}""").left.value is invalidParams
    blockflowFetch("""{"fromTs":42,"toTs":1}""").left.value is invalidParams
    blockflowFetch("""{"fromTs":1,"toTs":1000000000}""").left.value is invalidParams
    blockflowFetch("""{"toTs":1}""").left.value is invalidParams
    blockflowFetch("""{"fromTs":1}""").left.value is invalidParams
  }

  behavior of "ws"

  it should "receive one event" in new RouteWS {
    checkWS {
      sendEventAndCheck
    }
  }

  it should "receive multiple events" in new RouteWS {
    checkWS {
      (0 to 3).foreach { _ =>
        sendEventAndCheck
      }
    }
  }

  it should "complete on `complete` command" in new AlephiumActorSpec("Websocket") {
    val (actorRef, source) = RPCServerAbstract.Websocket.actorRef
    val sinkProbe          = source.runWith(TestSink.probe[String])
    val message            = "Hello"
    actorRef ! message
    actorRef ! RPCServerAbstract.Websocket.Completed
    sinkProbe.request(1).expectNext(message).expectComplete()
  }

  it should "stop on `Failed` command" in new AlephiumActorSpec("Websocket") {
    val (actorRef, source) = RPCServerAbstract.Websocket.actorRef
    val sinkProbe          = source.runWith(TestSink.probe[String])
    val message            = "Hello"
    actorRef ! message
    actorRef ! RPCServerAbstract.Websocket.Failed
    sinkProbe.request(1).expectNextOrError() match {
      case Right(hello) => hello is message
      case Left(error)  => error.getMessage is "failure on events websocket"
    }
  }

  trait Fixture extends InfoFixture with PlatformConfigFixture with StoragesFixture {
    val now = TimeStamp.now()

    lazy val dummyBlockHeader =
      ModelGen.blockGen.sample.get.header.copy(timestamp = (now - Duration.ofMinutes(5).get).get)
    lazy val dummyBlock           = ModelGen.blockGen.sample.get.copy(header = dummyBlockHeader)
    lazy val dummyFetchResponse   = FetchResponse(Seq(BlockEntry.from(dummyBlockHeader, 1)))
    lazy val dummyIntraCliqueInfo = genIntraCliqueInfo(config)
    lazy val dummySelfClique      = SelfClique.from(dummyIntraCliqueInfo)
    lazy val dummyBlockEntry      = BlockEntry.from(dummyBlock, 1)
    val dummyNeighborCliques      = NeighborCliques(AVector.empty)
    val dummyBalance              = Balance(0, 0)
    val dummyGroup                = Group(0)
    val dummyKey                  = "b4628b5e93e6356b8b2ce75174a57fbd2fe6907e6244d5ddfba78a94ebf9d7a5"
    val dummyKeyAddress           = "1EvRjvdiVH24YUgjfCA7RZVTWj4bKexks9iY33YbL14zt"
    val dummyToKey                = "3ec4489e0988fbe5ea1becd0804335cd78ae285883f4028009b0e69d0574cda9"
    val dummyToAddres             = "19kCCFBGJV76XjyzszeMGTbnDVkAYwhHtZGKEvc1JdJEG"
    val dummyPrivateKey           = "e89743f47eaef4d438b503e66de08f4eedd0d5d8c6ad9b9ff0177f081917ae1a"
    val dummyHashesAtHeight       = HashesAtHeight(Seq.empty)
    val dummyChainInfo            = ChainInfo(0)
    val dummyTx = ModelGen.transactionGen
      .retryUntil(tx => tx.unsigned.inputs.nonEmpty && tx.unsigned.fixedOutputs.nonEmpty)
      .sample
      .get
    val dummySignature = ED25519.sign(dummyTx.unsigned.hash.bytes,
                                      ED25519PrivateKey.unsafe(Hex.unsafe(dummyPrivateKey)))
    lazy val dummyTransferResult = TxResult(
      dummyTx.hash.toHexString,
      dummyTx.fromGroup.value,
      dummyTx.toGroup.value
    )
    lazy val dummyCreateTransactionResult = CreateTransactionResult(
      Hex.toHexString(serialize(dummyTx.unsigned)),
      dummyTx.unsigned.hash.toHexString
    )

    val blockFlowProbe = TestProbe()
  }

  trait RPCServerFixture extends Fixture {
    val minerProbe = TestProbe()
    val miner      = ActorRefT[Miner.Command](minerProbe.ref)

    lazy val mode: Mode = new ModeDummy(dummyIntraCliqueInfo,
                                        dummyNeighborCliques,
                                        dummyBlock,
                                        blockFlowProbe.ref,
                                        dummyTx,
                                        storages)

    lazy val server: RPCServer = RPCServer(mode, miner)
  }

  trait RouteHTTP extends RPCServerFixture {
    implicit lazy val askTimeout = Timeout(server.rpcConfig.askTimeout.asScala)

    def checkCall[T](method: String, params: Option[Json])(f: Response.Success => T): T = {
      rpcRequest(method, params.getOrElse(Json.obj()), 0) ~> server.httpRoute ~> check {
        status is StatusCodes.OK
        f(responseAs[Response.Success])
      }
    }

    def checkCallResult[T: Decoder](method: String, params: Option[Json] = None)(
        expected: T): Assertion =
      checkCall(method, params)(json => json.result.as[T] isE expected)

    def checkFailCallResult(method: String, params: Option[Json] = None)(errorMessage: String) =
      rpcRequest(method, params.getOrElse(Json.obj()), 0) ~> server.httpRoute ~> check {
        responseAs[Response.Failure].error.message is errorMessage
      }

    def rpcRequest(method: String, params: Json, id: Long): HttpRequest = {
      // scalastyle:off regex
      val jsonRequest = Request(method, params, id).asJson.noSpaces
      val entity      = HttpEntity(MediaTypes.`application/json`, jsonRequest)
      // scalastyle:on

      HttpRequest(HttpMethods.POST, entity = entity)
    }
  }

  trait RouteWS extends RPCServerFixture {
    val client = WSProbe()

    val blockNotify = BlockNotify(ModelGen.blockGen.sample.get.header, height = 0)
    def sendEventAndCheck: Assertion = {
      mode.node.eventBus ! blockNotify
      val TextMessage.Strict(message) = client.expectMessage()

      val json         = parse(message).toOption.get
      val notification = json.as[NotificationUnsafe].toOption.get.asNotification.toOption.get

      notification.method is "block_notify"
    }

    def checkWS[A](f: => A): A =
      WS("/events", client.flow) ~> server.wsRoute ~> check {
        isWebSocketUpgrade is true
        f
      }
  }
}

object RPCServerSpec {

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
      ActorRefT.build[EventBus.Message](system, EventBus.props(), s"EventBus-${Random.nextInt}")

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

    override def stop(): Future[Unit] =
      for {
        _ <- node.stop()
      } yield ()
  }
}
