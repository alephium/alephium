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

package org.alephium.app.ws

import java.util.UUID

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.DurationInt

import akka.actor.ActorSystem
import akka.testkit.TestProbe
import akka.util.Timeout
import io.vertx.core.Vertx
import io.vertx.core.buffer.Buffer
import io.vertx.core.http.{HttpServerOptions, WebSocketClientOptions, WebSocketFrame}
import org.scalacheck.Gen
import org.scalatest.{Assertion, BeforeAndAfterEach}
import org.scalatest.concurrent.{Eventually, IntegrationPatience, ScalaFutures}
import sttp.tapir.server.vertx.VertxFutureServerInterpreter.VertxFutureToScalaFuture

import org.alephium.api.model.{BlockAndEvents, ContractEvent, TransactionTemplate, ValU256}
import org.alephium.app.{ApiConfig, ServerFixture}
import org.alephium.app.ServerFixture.NodeDummy
import org.alephium.app.ws.WsParams.{
  ContractEventsSubscribeParams,
  WsBlockNotificationParams,
  WsContractEventNotificationParams,
  WsId,
  WsNotificationParams,
  WsSubscriptionId,
  WsTxNotificationParams
}
import org.alephium.app.ws.WsRequest.fromJsonString
import org.alephium.app.ws.WsSubscriptionHandler.{
  GetSubscriptions,
  SubscriptionMsg,
  WsImmutableSubscriptions
}
import org.alephium.app.ws.WsSubscriptionsState.{ContractEventKey, SubscriptionOfConnection}
import org.alephium.flow.handler.TestUtils
import org.alephium.json.Json.{read, reader, Reader}
import org.alephium.protocol.Hash
import org.alephium.protocol.model.{Address, ContractId}
import org.alephium.protocol.vm.{LogState, Val}
import org.alephium.util._

trait WsFixture extends ServerFixture {

  protected lazy val wsIdGen: Gen[WsId] = Gen.const("__vertx.ws.").map(_ + UUID.randomUUID())

  protected lazy val wsId_0 = wsIdGen.sample.get
  protected lazy val wsId_1 = wsIdGen.sample.get

  protected lazy val contractAddressGen: Gen[Address.Contract] = for {
    group        <- groupIndexGen
    lockupScript <- p2cLockupGen(group)
  } yield Address.Contract(lockupScript)

  protected lazy val EventIndex_0 = Gen.choose(1, 10).sample.get
  protected lazy val EventIndex_1 = Gen.choose(11, 20).sample.get

  protected lazy val contractAddress_0: Address.Contract = contractAddressGen.sample.get
  protected lazy val contractAddress_1: Address.Contract = contractAddressGen.sample.get
  protected lazy val contractAddress_2: Address.Contract = contractAddressGen.sample.get
  protected lazy val contractAddress_3: Address.Contract = contractAddressGen.sample.get

  protected lazy val params_addr_01_eventIndex_0 = ContractEventsSubscribeParams
    .from(
      AVector(contractAddress_0, contractAddress_1),
      Some(EventIndex_0)
    )
  protected lazy val params_addr_12_eventIndex_1 = ContractEventsSubscribeParams
    .from(
      AVector(contractAddress_1, contractAddress_2),
      Some(EventIndex_1)
    )
  protected lazy val params_addr_2_eventIndex_1 = ContractEventsSubscribeParams.fromSingle(
    contractAddress_2,
    Some(EventIndex_1)
  )
  protected lazy val params_addr_3_unfiltered = ContractEventsSubscribeParams.fromSingle(
    contractAddress_3,
    None
  )

  protected lazy val duplicateAddresses = AVector(contractAddress_0, contractAddress_0)

  protected lazy val tooManyContractAddresses: AVector[Address.Contract] =
    Gen
      .listOfN(networkConfig.wsMaxContractEventAddresses + 1, contractAddressGen)
      .map(AVector.from)
      .sample
      .get

  protected lazy val contractEvent = {
    ContractEvent(
      blockHashGen.sample.get,
      txIdGen.sample.get,
      contractAddressGen.sample.get,
      EventIndex_0,
      AVector(ValU256(U256.unsafe(5)))
    )
  }
}

trait WsClientServerFixture
    extends AlephiumSpec
    with WsSubscriptionFixture
    with ServerFixture
    with ScalaFutures
    with Eventually
    with IntegrationPatience
    with BeforeAndAfterEach {

  override val configValues = configPortsValues

  implicit protected lazy val apiConfig: ApiConfig = ApiConfig.load(newConfig)
  implicit protected val timeout: Timeout          = Timeout(Duration.ofSecondsUnsafe(5).asScala)
  implicit protected val system: ActorSystem       = ActorSystem("WsServerFixtureSystem")
  implicit protected val executionContext: ExecutionContext = system.dispatcher

  protected lazy val vertx            = Vertx.vertx()
  protected lazy val blockFlowProbe   = TestProbe()
  protected lazy val (allHandlers, _) = TestUtils.createAllHandlersProbe
  protected lazy val node = new NodeDummy(
    dummyIntraCliqueInfo,
    dummyNeighborPeers,
    dummyBlock,
    blockFlowProbe.ref,
    allHandlers,
    dummyTx,
    dummyContract,
    storages
  )

  protected lazy val wsPort: Int = config.network.restPort
  protected lazy val wsOptions =
    new HttpServerOptions()
      .setMaxWebSocketFrameSize(config.network.wsMaxFrameSize)
      .setRegisterWebSocketWriteHandlers(true)

  protected def maxServerConnections: Int   = 10
  protected def maxClientConnections: Int   = 500
  protected def keepAliveInterval: Duration = Duration.ofSecondsUnsafe(10)

  protected def bindAndListen(): WsServer = {
    val wsServer =
      WsServer(
        system,
        node,
        maxServerConnections,
        config.network.wsMaxSubscriptionsPerConnection,
        config.network.wsMaxContractEventAddresses,
        keepAliveInterval,
        wsOptions
      )
    wsServer.httpServer
      .listen(config.network.restPort, apiConfig.networkInterface.getHostAddress)
      .asScala
      .futureValue
    wsServer
  }

  protected lazy val WsServer(httpServer, eventHandler, subscriptionHandler) = bindAndListen()
  protected lazy val wsClient: WsClient = {
    httpServer.actualPort() is config.network.restPort
    testEventHandlerInitialized(eventHandler)
    testSubscriptionHandlerInitialized(subscriptionHandler)
    WsClient(
      vertx,
      new WebSocketClientOptions()
        .setMaxFrameSize(config.network.wsMaxFrameSize)
        .setMaxConnections(maxClientConnections)
    )
  }

  // scalastyle:off regex
  protected def measureTime[T](operationName: String)(operation: => T): T = {
    val startTime      = System.currentTimeMillis()
    val result         = operation
    val endTime        = System.currentTimeMillis()
    val durationMillis = endTime - startTime
    println(s"It took $durationMillis ms to execute $operationName")
    result
  }
  // scalastyle:on regex

  def testWsAndClose[A](wsF: Future[ClientWs])(testCode: ClientWs => A): A = {
    val ws = wsF.futureValue
    try {
      testCode(ws)
    } finally {
      ws.close().futureValue
    }
  }

  protected def testEventHandlerInitialized(
      eventHandler: ActorRefT[EventBus.Message]
  ): Assertion = {
    node.eventBus
      .ask(EventBus.ListSubscribers)
      .mapTo[EventBus.Subscribers]
      .futureValue
      .value
      .contains(eventHandler.ref) is true
  }

  override def afterAll(): Unit = {
    super.afterAll()
    httpServer.close().asScala.futureValue
    system.terminate().futureValue
    ()
  }
}

trait WsSubscriptionFixture extends ServerFixture with WsFixture with ScalaFutures with Eventually {
  protected def dummyServerWs(id: String): ServerWsLike = new ServerWsLike {
    override def textHandlerID(): WsId                                       = id
    override def isClosed: Boolean                                           = false
    override def reject(statusCode: Int): Unit                               = ()
    override def closeHandler(handler: () => Unit): ServerWsLike             = this
    override def textMessageHandler(handler: String => Unit): ServerWsLike   = this
    override def frameHandler(handler: WebSocketFrame => Unit): ServerWsLike = this
    override def writeTextMessage(msg: String): Future[Unit]                 = Future.successful(())
    override def writePong(data: Buffer): Future[Unit]                       = Future.successful(())
    override def writePing(data: Buffer): Future[Unit]                       = Future.successful(())
  }

  protected def testSubscriptionHandlerInitialized(
      subscriptionHandler: ActorRefT[SubscriptionMsg]
  ): Assertion = {
    subscriptionHandler
      .ask(GetSubscriptions)(Timeout(100.millis))
      .mapTo[WsImmutableSubscriptions]
      .futureValue
      .subscriptions
      .size is 0
  }

  protected def getSubscriptions(
      subscriptionHandler: ActorRefT[WsSubscriptionHandler.SubscriptionMsg]
  ): WsImmutableSubscriptions =
    subscriptionHandler
      .ask(GetSubscriptions)(Timeout(100.millis))
      .mapTo[WsImmutableSubscriptions]
      .futureValue

  protected def flattenParams(
      wsId: WsId,
      paramss: AVector[ContractEventsSubscribeParams]
  ): AVector[(SubscriptionOfConnection, ContractEventKey)] = {
    assume(paramss.length == paramss.map(_.subscriptionId).toSet.size)
    paramss.flatMap { params =>
      params.toContractEventKeys.map { contractKey =>
        SubscriptionOfConnection(wsId, params.subscriptionId) -> contractKey
      }
    }
  }

  protected def logStatesFor(
      contractIdsWithEventIndex: AVector[(ContractId, Int)]
  ): AVector[(ContractId, LogState)] = {
    contractIdsWithEventIndex.map { case (contractId, eventIndex) =>
      contractId -> LogState(
        txIdGen.sample.get,
        eventIndex.toByte,
        AVector(Val.U256(U256.unsafe(1)))
      )
    }
  }

  protected def assertConnectedButNotSubscribed(
      wsId: WsId,
      subscriptionId: WsSubscriptionId,
      subscriptionHandler: ActorRefT[WsSubscriptionHandler.SubscriptionMsg]
  ): Assertion = {
    getSubscriptions(subscriptionHandler).subscriptions
      .find(_._1 == wsId)
      .exists(_._2.filter(_._1 == subscriptionId).length == 0) is true
  }

  protected def assertNotConnected(
      wsId: WsId,
      subscriptionHandler: ActorRefT[WsSubscriptionHandler.SubscriptionMsg]
  ): Assertion = {
    !getSubscriptions(subscriptionHandler).subscriptions.exists(_._1 == wsId) is true
  }

  implicit protected val wsNotificationParamsReader: Reader[WsNotificationParams] =
    reader[ujson.Value].map[WsNotificationParams] {
      case ujson.Obj(values) if values.contains("subscription") =>
        val subscription = Hash.unsafe(Hex.unsafe(values("subscription").str))
        values("result") match {
          case obj: ujson.Obj if obj.value.contains("block") =>
            WsBlockNotificationParams(subscription, read[BlockAndEvents](obj))
          case obj: ujson.Obj if obj.value.contains("unsigned") =>
            WsTxNotificationParams(subscription, read[TransactionTemplate](obj))
          case obj: ujson.Obj if obj.value.contains("contractAddress") =>
            WsContractEventNotificationParams(subscription, read[ContractEvent](obj))
          case other =>
            throw new Exception(s"Expected ujson.Obj for 'result', got: $other")
        }
      case other =>
        throw new Exception(s"Invalid JSON format for WsNotificationParams: $other")
    }

  implicit protected val wsRequestReader: Reader[WsRequest] =
    reader[ujson.Value].map[WsRequest] { json =>
      fromJsonString(
        json.render(),
        config.network.wsMaxContractEventAddresses
      ) match {
        case Right(wsRequest) => wsRequest
        case Left(failure)    => throw failure.error
      }
    }
}

trait WsBehaviorFixture extends WsClientServerFixture {
  import org.alephium.app.ws.WsSubscriptionHandler._
  import org.alephium.app.ws.WsBehaviorFixture._

  protected def checkWS(
      initBehaviors: AVector[WsStartBehavior],
      nextBehaviors: AVector[WsNextBehavior],
      expectedSubscriptions: Int,
      openWebsocketsCount: Int
  ): Assertion = {
    eventually(testEventHandlerInitialized(eventHandler))

    val probedSockets =
      initBehaviors.map { case WsStartBehavior(startBehavior, _, _) =>
        val clientProbe = TestProbe()
        val ws          = startBehavior(clientProbe).futureValue
        ws -> clientProbe
      }
    try {
      initBehaviors.foreach { case WsStartBehavior(_, serverBehavior, clientAssertionOnMsg) =>
        serverBehavior(node.eventBus)
        probedSockets.foreach { case (_, clientProbe) =>
          clientAssertionOnMsg(clientProbe)
        }
      }
      probedSockets.foreach { case (ws, clientProbe) =>
        nextBehaviors.foreach {
          case WsNextBehavior(behavior, serverBehavior, clientAssertionOnMsg) =>
            behavior(ws).futureValue
            serverBehavior(node.eventBus)
            clientAssertionOnMsg(clientProbe)
        }
      }
      eventually {
        probedSockets.filterNot(_._1.isClosed).length is openWebsocketsCount
        subscriptionHandler
          .ask(GetSubscriptions)
          .mapTo[WsImmutableSubscriptions]
          .futureValue
          .subscriptions
          .map(_._2.length)
          .sum is expectedSubscriptions
      }
    } finally {
      Future.sequence(probedSockets.map(_._1.close()).iterator).futureValue
      ()
    }
  }
}

object WsBehaviorFixture {

  sealed trait WsBehavior
  final case class WsStartBehavior(
      clientInitBehavior: TestProbe => Future[ClientWs],
      serverBehavior: ActorRefT[EventBus.Message] => Unit,
      clientAssertionOnMsg: TestProbe => Any
  ) extends WsBehavior

  final case class WsNextBehavior(
      clientInitBehavior: ClientWs => Future[Unit],
      serverBehavior: ActorRefT[EventBus.Message] => Unit,
      clientAssertionOnMsg: TestProbe => Any
  ) extends WsBehavior
}
