package org.alephium.appserver

import scala.util.Random

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.ws.TextMessage
import akka.http.scaladsl.server.{MethodRejection, UnsupportedRequestContentTypeRejection}
import akka.http.scaladsl.testkit.{ScalatestRouteTest, WSProbe}
import akka.stream.testkit.scaladsl.TestSink
import akka.testkit.TestProbe
import akka.util.{ByteString, Timeout}
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._
import io.circe.{Decoder, Encoder, Json}
import io.circe.parser._
import io.circe.syntax._
import org.scalatest.{Assertion, EitherValues}
import org.scalatest.concurrent.ScalaFutures

import org.alephium.appserver.RPCModel._
import org.alephium.crypto.{ED25519PrivateKey, ED25519PublicKey, Keccak256}
import org.alephium.flow.client.{Miner, Node}
import org.alephium.flow.core.{AllHandlers, BlockFlow}
import org.alephium.flow.core.FlowHandler.BlockNotify
import org.alephium.flow.io.IOResult
import org.alephium.flow.model.BlockDeps
import org.alephium.flow.network.{Bootstrapper, CliqueManager, DiscoveryServer, TcpServer}
import org.alephium.flow.network.bootstrap.{InfoFixture, IntraCliqueInfo}
import org.alephium.flow.platform.{Mode, PlatformConfig, PlatformConfigFixture}
import org.alephium.protocol.model._
import org.alephium.rpc.CirceUtils
import org.alephium.rpc.model.JsonRPC._
import org.alephium.util._

class RPCServerSpec
    extends AlephiumSpec
    with ScalatestRouteTest
    with EitherValues
    with ScalaFutures {
  import RPCServerSpec._

  behavior of "RPCServer"
  behavior of "http"

  it should "encode BlockNotify" in new Fixture {
    val header =
      BlockHeader(AVector(Keccak256.hash("foo")), Keccak256.hash("bar"), TimeStamp.zero, 1, 2)
    val blockNotify = BlockNotify(header, 1)

    val result = RPCServer.blockNotifyEncode(blockNotify)

    show(result) is """{"hash":"62c38e6d","timestamp":0,"chainFrom":0,"chainTo":2,"height":1,"deps":["de098c4d"]}"""
  }

  it should "call mining_start" in new RouteHTTP {
    checkCallResult("mining_start")(true)
    minerProbe.expectMsg(Miner.Start)
  }

  it should "call mining_stop" in new RouteHTTP {
    checkCallResult("mining_stop")(true)
    minerProbe.expectMsg(Miner.Stop)
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
    checkCallResult("get_balance", parse(s"""{"address":"$dummyKey","type":"pkh"}""").toOption)(
      dummyBalance)
  }

  it should "reject wrong get_balance call" in new RouteHTTP {
    checkFailCallResult("get_balance", parse(s"""{"address":"OOPS","type":"pkh"}""").toOption)(
      "Server error")
    checkFailCallResult(
      "get_balance",
      parse(s"""{"address":"$dummyKey","type":"OOPS"}""").toOption)("Server error")
    checkFailCallResult("get_balance", parse(s"""{"OOPS":"OOPS"}""").toOption)("Invalid params")
  }

  it should "call get_group" in new RouteHTTP {
    checkCallResult("get_group", parse(s"""{"address":"$dummyKey"}""").toOption)(dummyGroup)
  }

  it should "call transfer" in new RouteHTTP {
    checkCallResult(
      "transfer",
      parse(
        s"""{"fromAddress":"$dummyKey","fromType":"pkh","toAddress":"$dummyToAddres","toType":"pkh","value":1,"fromPrivateKey":"$dummyPrivateKey"}""").toOption
    )(dummyTransferResult)
  }

  it should "reject wrong transfer call" in new RouteHTTP {
    checkFailCallResult(
      "transfer",
      parse(
        s"""{"fromAddress":"$dummyKey","fromType":"OOPS","toAddress":"$dummyToAddres","toType":"OOPS","value":1,"fromPrivateKey":"$dummyPrivateKey"}""").toOption
    )("Server error")

    checkFailCallResult(
      "transfer",
      parse(
        s"""{"fromAddress":"OOPS","fromType":"pkh","toAddress":"$dummyToAddres","toType":"pkh","value":1,"fromPrivateKey":"$dummyPrivateKey"}""").toOption
    )("Server error")
  }

  it should "reject when address belongs to other groups" in new RouteHTTP {
    override val configValues = Map(
      ("alephium.broker.brokerId", 1)
    )
    checkFailCallResult("get_balance", parse(s"""{"address":"$dummyKey","type":"pkh"}""").toOption)(
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
    server.stopServer().futureValue is akka.Done
  }

  it should "make sure rps and ws port are provided" in new Fixture {
    override val configValues = Map(
      ("alephium.network.rpcPort", 0),
      ("alephium.network.wsPort", 0)
    )

    val mode: Mode = new ModeDummy(dummyIntraCliqueInfo,
                                   dummyNeighborCliques,
                                   dummmyBlockHeader,
                                   blockFlowProbe.ref)

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

    val blockFlow = new BlockFlowDummy(dummmyBlockHeader, blockFlowProbe.ref)
    def blockflowFetch(params: String) = {
      RPCServer.blockflowFetch(blockFlow, Request("blockflow_fetch", parse(params).right.value, 0))
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

  trait Fixture extends InfoFixture with PlatformConfigFixture {

    val now = TimeStamp.now()
    lazy val dummmyBlockHeader =
      ModelGen.blockGen.sample.get.header.copy(timestamp = (now - Duration.ofMinutes(5).get).get)
    lazy val dummyFetchResponse   = FetchResponse(Seq(BlockEntry.from(dummmyBlockHeader, 1)))
    lazy val dummyIntraCliqueInfo = genIntraCliqueInfo(config)
    lazy val dummySelfClique      = SelfClique.from(dummyIntraCliqueInfo)
    val dummyNeighborCliques      = NeighborCliques(AVector.empty)
    val dummyBalance              = Balance(0, 0)
    val dummyGroup                = Group(0)
    val dummyTransferResult = TransferResult(
      "011b4d03dd8c01f1049143cf9c4c817e4b167f1d1b83e5c6f0f10d89ba1e7bce")
    val dummyKey        = "4b67a9704059abf76b5d75be94b0d16a85dd66d7dc106fcc2dd200bab0f45f77"
    val dummyToAddres   = "4681f79b0225c208e1dee62fe05af3e02a58571a0b668ea5472f35da7acc2f13"
    val dummyPrivateKey = "b0e218ff0d40482d37bb787dccc7a4c9a6d56c26885f66c6b5ce23c87c891f5e"
    val blockFlowProbe  = TestProbe()
  }

  trait RPCServerFixture extends Fixture {
    val minerProbe = TestProbe()
    val miner      = ActorRefT[Miner.Command](minerProbe.ref)

    lazy val mode: Mode = new ModeDummy(dummyIntraCliqueInfo,
                                        dummyNeighborCliques,
                                        dummmyBlockHeader,
                                        blockFlowProbe.ref)

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
      checkCall(method, params)(json => json.result.as[T].right.value is expected)

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

      val json         = parse(message).right.value
      val notification = json.as[NotificationUnsafe].right.value.asNotification.right.value

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
      case DiscoveryServer.GetNeighborCliques => sender() ! neighborCliques
    }
  }

  class BootstrapperDummy(intraCliqueInfo: IntraCliqueInfo) extends BaseActor {
    def receive: Receive = {
      case Bootstrapper.GetIntraCliqueInfo => sender() ! intraCliqueInfo
    }
  }

  class NodeDummy(intraCliqueInfo: IntraCliqueInfo,
                  neighborCliques: NeighborCliques,
                  blockHeader: BlockHeader,
                  blockFlowProbe: ActorRef)(implicit val config: PlatformConfig)
      extends Node {
    implicit val system: ActorSystem = ActorSystem("NodeDummy")
    val blockFlow: BlockFlow         = new BlockFlowDummy(blockHeader, blockFlowProbe)

    val serverProbe                          = TestProbe()
    val server: ActorRefT[TcpServer.Command] = ActorRefT(serverProbe.ref)

    val eventBus =
      ActorRefT.build[EventBus.Message](system, EventBus.props(), s"EventBus-${Random.nextInt}")

    val discoveryServerDummy                                = system.actorOf(Props(new DiscoveryServerDummy(neighborCliques)))
    val discoveryServer: ActorRefT[DiscoveryServer.Command] = ActorRefT(discoveryServerDummy)

    val cliqueManagerProbe                              = TestProbe()
    val cliqueManager: ActorRefT[CliqueManager.Command] = ActorRefT(cliqueManagerProbe.ref)

    val allHandlers: AllHandlers = AllHandlers(flowHandler = ActorRefT(TestProbe().ref),
                                               txHandler      = ActorRefT(TestProbe().ref),
                                               blockHandlers  = Map.empty,
                                               headerHandlers = Map.empty)

    val boostraperDummy                             = system.actorOf(Props(new BootstrapperDummy(intraCliqueInfo)))
    val boostraper: ActorRefT[Bootstrapper.Command] = ActorRefT(boostraperDummy)
  }

  class BlockFlowDummy(blockHeader: BlockHeader, blockFlowProbe: ActorRef)(
      implicit val config: PlatformConfig)
      extends BlockFlow {
    override def getHeadersUnsafe(predicate: BlockHeader => Boolean): Seq[BlockHeader] = {
      blockFlowProbe ! predicate(blockHeader)
      Seq(blockHeader)
    }
    override def prepareP2pkhTx(
        from: ED25519PublicKey,
        to: ED25519PublicKey,
        value: BigInt,
        fromPrivateKey: ED25519PrivateKey): IOResult[Option[Transaction]] = {
      Right(Some(
        Transaction(RawTransaction(AVector.empty, AVector.empty), ByteString.empty, AVector.empty)))
    }

    override def getHeight(hash: Keccak256): Int = {
      1
    }
    def getOutBlockTips(brokerInfo: BrokerInfo): AVector[Keccak256]          = ???
    def calBestDepsUnsafe(group: GroupIndex): BlockDeps                      = ???
    def getAllTips: org.alephium.util.AVector[org.alephium.crypto.Keccak256] = ???
    def getBestTip: org.alephium.crypto.Keccak256                            = ???
    def add(header: org.alephium.protocol.model.BlockHeader,
            parentHash: org.alephium.crypto.Keccak256,
            weight: Int): org.alephium.flow.io.IOResult[Unit] = ???
    def add(header: org.alephium.protocol.model.BlockHeader,
            weight: Int): org.alephium.flow.io.IOResult[Unit] = ???
    def add(block: org.alephium.protocol.model.Block,
            parentHash: org.alephium.crypto.Keccak256,
            weight: Int): org.alephium.flow.io.IOResult[Unit] = ???
    def add(block: org.alephium.protocol.model.Block,
            weight: Int): org.alephium.flow.io.IOResult[Unit]                              = ???
    def calBestDepsUnsafe(): Unit                                                          = ???
    def updateBestDeps(): org.alephium.flow.io.IOResult[Unit]                              = ???
    def add(block: org.alephium.protocol.model.Block): org.alephium.flow.io.IOResult[Unit] = ???
    def add(header: org.alephium.protocol.model.BlockHeader): org.alephium.flow.io.IOResult[Unit] =
      ???
  }

  class ModeDummy(intraCliqueInfo: IntraCliqueInfo,
                  neighborCliques: NeighborCliques,
                  blockHeader: BlockHeader,
                  blockFlowProbe: ActorRef)(implicit val config: PlatformConfig)
      extends Mode {
    val node = new NodeDummy(intraCliqueInfo, neighborCliques, blockHeader, blockFlowProbe)
  }

}
