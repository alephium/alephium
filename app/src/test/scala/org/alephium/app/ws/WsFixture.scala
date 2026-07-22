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
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.util.{Failure, Success}

import io.vertx.core.Vertx
import io.vertx.core.buffer.Buffer
import io.vertx.core.http.{HttpServerOptions, WebSocketClientOptions, WebSocketFrame}
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.testkit.TestProbe
import org.apache.pekko.util.Timeout
import org.scalacheck.Gen
import org.scalatest.{Assertion, BeforeAndAfterEach}
import org.scalatest.concurrent.{
  Eventually,
  IntegrationPatience,
  PatienceConfiguration,
  ScalaFutures
}
import sttp.tapir.server.vertx.VertxFutureServerInterpreter.VertxFutureToScalaFuture

import org.alephium.api.model.{BlockAndEvents, ContractEvent, TransactionTemplate, ValU256}
import org.alephium.app.{ApiConfig, ServerFixture}
import org.alephium.app.ServerFixture.NodeDummy
import org.alephium.app.ws.WsServer
import org.alephium.flow.handler.TestUtils
import org.alephium.json.Json.{read, reader, Reader}
import org.alephium.protocol.Hash
import org.alephium.protocol.model.{Address, ContractId}
import org.alephium.protocol.vm.{LogState, Val}
import org.alephium.util._
import org.alephium.ws._
import org.alephium.ws.WsParams.{
  ContractEventsSubscribeParams,
  WsBlockNotificationParams,
  WsContractEventNotificationParams,
  WsCorrelationId,
  WsId,
  WsNotificationParams,
  WsSubscriptionId,
  WsTxNotificationParams
}
import org.alephium.ws.WsRequest.fromJsonString
import org.alephium.ws.WsSubscriptionHandler.{
  GetSubscriptions,
  SubscriptionMsg,
  WsImmutableSubscriptions
}
import org.alephium.ws.WsSubscriptionsState.{
  buildContractEventKeys,
  ContractEventKey,
  SubscriptionOfConnection
}

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

  protected lazy val params_addr_01_eventIndex_0 = ContractEventsSubscribeParams(
    AVector(contractAddress_0, contractAddress_1),
    Some(EventIndex_0)
  )
  protected lazy val params_addr_12_eventIndex_1 = ContractEventsSubscribeParams(
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
      .listOfN(networkConfig.ws.maxContractEventAddresses + 1, contractAddressGen)
      .map(AVector.from)
      .sample
      .get

  protected lazy val contractEvent = {
    ContractEvent(
      blockHashGen.sample.get,
      txIdGen.sample.get,
      timestampGen.sample.get,
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

  @volatile private var startedWsServer: Option[WsServer] = None
  @volatile private var clientVertxStarted: Boolean       = false
  @volatile private var stopped: Boolean                  = false

  AlephiumSpec.addCleanTask(() => stopFixture())

  protected lazy val vertx = {
    clientVertxStarted = true
    Vertx.vertx()
  }
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
      .setMaxWebSocketFrameSize(config.network.ws.maxFrameSize)
      .setRegisterWebSocketWriteHandlers(true)

  protected def maxServerConnections: Int   = 10
  protected def maxClientConnections: Int   = 500
  protected def maxRequestsPerSecond: Int   = config.network.ws.maxRequestsPerSecond
  protected def maxWriteQueueSize: Int      = config.network.ws.maxWriteQueueSize
  protected def keepAliveInterval: Duration = Duration.ofSecondsUnsafe(10)

  val httpService = new org.alephium.http.HttpService(wsOptions)(executionContext)
  protected def bindAndListen(): WsServer = {
    val wsServer =
      new WsServer(
        httpService,
        system,
        node,
        maxServerConnections,
        apiConfig.apiKey,
        maxRequestsPerSecond,
        maxWriteQueueSize,
        config.network.ws.maxSubscriptionsPerConnection,
        config.network.ws.maxContractEventAddresses,
        keepAliveInterval
      )(config.network, config.broker, executionContext)

    // Start the services
    wsServer.start().futureValue
    startedWsServer = Some(wsServer)

    // Listen on the port
    httpService
      .listen(config.network.restPort, apiConfig.networkInterface.getHostAddress)
      .futureValue

    wsServer
  }

  protected lazy val wsServer0 = bindAndListen()
  protected lazy val httpServer = {
    wsServer0 // Force initialization
    httpService.httpServer
  }
  protected lazy val eventHandler        = wsServer0.eventHandler
  protected lazy val subscriptionHandler = wsServer0.subscriptionHandler
  protected lazy val wsClient: WsClientFactory = {
    httpServer.actualPort() is config.network.restPort
    testEventHandlerInitialized(eventHandler)
    testSubscriptionHandlerInitialized(subscriptionHandler)
    WsClientFactory(
      vertx,
      new WebSocketClientOptions()
        .setMaxFrameSize(config.network.ws.maxFrameSize)
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

  def testWsAndClose[A](wsF: => Future[WsClient])(testCode: WsClient => A): A = {
    val previousConnections = getSubscriptions(subscriptionHandler).activeConnections
    val ws                  = wsF.futureValue
    val newConnections      = waitUntilNewWsConnection(previousConnections)
    try {
      testCode(ws)
    } finally {
      ws.close().futureValue
      val _ = eventually(PatienceConfiguration.Timeout(15.seconds)) {
        getSubscriptions(subscriptionHandler).activeConnections
          .intersect(newConnections)
          .isEmpty is true
      }
      ()
    }
  }

  protected def connectAndWait(wsF: => Future[WsClient]): WsClient = {
    val previousConnections = getSubscriptions(subscriptionHandler).activeConnections
    val ws                  = wsF.futureValue
    waitUntilNewWsConnection(previousConnections)
    ws
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

  protected def waitUntilNewWsConnection(previousConnections: Set[WsId]): Set[WsId] = {
    eventually(PatienceConfiguration.Timeout(15.seconds)) {
      val newConnections =
        getSubscriptions(subscriptionHandler).activeConnections.diff(previousConnections)
      newConnections.nonEmpty is true
      newConnections
    }
  }

  private def stopFixture(): Unit = {
    if (!stopped) {
      synchronized {
        if (!stopped) {
          stopped = true
          startedWsServer.foreach(_.stop().futureValue)
          if (clientVertxStarted) {
            vertx.close().asScala.futureValue
          }
          system.terminate().futureValue
        }
      }
    }
    ()
  }

  override def afterAll(): Unit = {
    stopFixture()
    super.afterAll()
  }
}

trait WsSubscriptionFixture extends ServerFixture with WsFixture with ScalaFutures with Eventually {
  protected def dummyServerWs(id: String): ServerWsLike = new ServerWsLike {
    override def textHandlerID(): WsId                                       = id
    override def isClosed: Boolean                                           = false
    override def closeHandler(handler: () => Unit): ServerWsLike             = this
    override def textMessageHandler(handler: String => Unit): ServerWsLike   = this
    override def frameHandler(handler: WebSocketFrame => Unit): ServerWsLike = this
    override def setWriteQueueMaxSize(maxSize: Int): ServerWsLike            = this
    override def writeQueueFull: Boolean                                     = false
    override def writeTextMessage(msg: String): Future[Unit]                 = Future.successful(())
    override def writePing(data: Buffer): Future[Unit]                       = Future.successful(())
    override def close(): Future[Unit]                                       = Future.successful(())
  }

  protected def makeSubscriptionHandler(
      system: ActorSystem,
      maxRequestsPerSecond: Int = config.network.ws.maxRequestsPerSecond,
      pingFrequency: FiniteDuration = FiniteDuration(
        config.network.ws.pingFrequency.millis,
        TimeUnit.MILLISECONDS
      )
  ): ActorRefT[SubscriptionMsg] =
    WsSubscriptionHandler.apply(
      Vertx.vertx(),
      system,
      new AtomicInteger(0),
      maxRequestsPerSecond,
      config.network.ws.maxWriteQueueSize,
      config.network.ws.maxSubscriptionsPerConnection,
      config.network.ws.maxContractEventAddresses,
      pingFrequency
    )

  protected def corId(n: Long): WsCorrelationId = n

  protected def testSubscriptionHandlerInitialized(
      subscriptionHandler: ActorRefT[SubscriptionMsg]
  ): Assertion = {
    subscriptionHandler
      .ask(GetSubscriptions)(Timeout(100.millis))
      .mapTo[WsImmutableSubscriptions]
      .futureValue
      .connections
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
      buildContractEventKeys(params).map { contractKey =>
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
    getSubscriptions(subscriptionHandler).connections
      .find(_._1 == wsId)
      .exists(_._2.filter(_._1 == subscriptionId).length == 0) is true
  }

  protected def assertNotConnected(
      wsId: WsId,
      subscriptionHandler: ActorRefT[WsSubscriptionHandler.SubscriptionMsg]
  ): Assertion = {
    !getSubscriptions(subscriptionHandler).connections.exists(_._1 == wsId) is true
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
        config.network.ws.maxContractEventAddresses
      ) match {
        case Right(wsRequest) => wsRequest
        case Left(failure)    => throw failure.error
      }
    }
}

trait WsBehaviorFixture extends WsClientServerFixture {
  import org.alephium.ws.WsSubscriptionHandler._
  import WsBehaviorFixture._

  protected def checkWS(
      initBehaviors: AVector[WsStartBehavior],
      nextBehaviors: AVector[WsNextBehavior],
      expectedSubscriptions: Int,
      openWebsocketsCount: Int
  ): Assertion = {
    eventually(testEventHandlerInitialized(eventHandler))

    val probedSockets =
      initBehaviors.map(_.clientInitBehavior).map { clientInitBehavior =>
        val clientProbe = TestProbe()
        val wsEither = clientInitBehavior(clientProbe).transform {
          case Success(value)     => Success(Right(value))
          case Failure(exception) => Success(Left(exception))
        }.futureValue
        wsEither -> clientProbe
      }
    try {
      initBehaviors.foreach { case WsStartBehavior(_, serverBehavior, clientAssertionOnMsg) =>
        serverBehavior(node.eventBus)
        probedSockets.foreach { case (wsEither, clientProbe) =>
          clientAssertionOnMsg(wsEither, clientProbe)
        }
      }
      probedSockets.foreach { case (wsEither, clientProbe) =>
        nextBehaviors.foreach {
          case WsNextBehavior(clientInitBehavior, serverBehavior, clientAssertionOnMsg) =>
            clientInitBehavior(wsEither)
            serverBehavior(node.eventBus)
            clientAssertionOnMsg(clientProbe)
        }
      }
      eventually {
        probedSockets
          .map(_._1)
          .filter(ws => ws.isRight && !ws.rightValue.isClosed)
          .length is openWebsocketsCount
        subscriptionHandler
          .ask(GetSubscriptions)
          .mapTo[WsImmutableSubscriptions]
          .futureValue
          .connections
          .map(_._2.length)
          .sum is expectedSubscriptions
      }
    } finally {
      probedSockets.foreach(_._1.map(_.close()))
      ()
    }
  }
}

object WsBehaviorFixture {

  sealed trait WsBehavior
  final case class WsStartBehavior(
      clientInitBehavior: TestProbe => Future[WsClient],
      serverBehavior: ActorRefT[EventBus.Message] => Unit,
      clientAssertionOnMsg: (Either[Throwable, WsClient], TestProbe) => Any
  ) extends WsBehavior

  final case class WsNextBehavior(
      clientInitBehavior: Either[Throwable, WsClient] => Any,
      serverBehavior: ActorRefT[EventBus.Message] => Unit,
      clientAssertionOnMsg: TestProbe => Any
  ) extends WsBehavior
}
