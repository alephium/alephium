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

import scala.concurrent.{ExecutionContext, Future}

import akka.actor.ActorSystem
import akka.testkit.TestProbe
import akka.util.Timeout
import io.vertx.core.Vertx
import io.vertx.core.buffer.Buffer
import io.vertx.core.http.{HttpServerOptions, WebSocketClientOptions, WebSocketFrame}
import org.scalatest.Assertion
import org.scalatest.concurrent.{Eventually, IntegrationPatience, ScalaFutures}
import sttp.tapir.server.vertx.VertxFutureServerInterpreter._

import org.alephium.api.ApiModelCodec
import org.alephium.api.model.{
  BlockAndEvents,
  ContractEvent,
  ContractEventByBlockHash,
  TransactionTemplate,
  ValU256
}
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
  AddressWithIndex,
  GetSubscriptions,
  SubscriptionMsg,
  SubscriptionOfConnection,
  WsImmutableSubscriptions
}
import org.alephium.flow.handler.TestUtils
import org.alephium.json.Json.{read, reader, Reader}
import org.alephium.protocol.Hash
import org.alephium.protocol.model.{Address, ContractId}
import org.alephium.protocol.vm.{LockupScript, LogState, LogStateRef, LogStatesId, Val}
import org.alephium.util._

trait WsFixture extends AlephiumSpec with ApiModelCodec {
  protected val EventIndex_0 = 0
  protected val EventIndex_1 = 1

  protected lazy val contractAddress_0: Address.Contract = Address.Contract(
    LockupScript.p2c(
      ContractId.zero
    )
  )
  protected lazy val contractAddress_1: Address.Contract = Address.Contract(
    LockupScript.p2c(
      ContractId.generate
    )
  )
  protected lazy val contractAddress_2: Address.Contract = Address.Contract(
    LockupScript.p2c(
      ContractId.generate
    )
  )

  protected lazy val contractEventsParams_0 = ContractEventsSubscribeParams
    .from(
      EventIndex_0,
      AVector(contractAddress_0, contractAddress_1)
    )
  protected lazy val contractEventsParams_1 = ContractEventsSubscribeParams
    .from(
      EventIndex_1,
      AVector(contractAddress_1, contractAddress_2)
    )
  protected lazy val contractEventsParams_2 = ContractEventsSubscribeParams.fromSingle(
    EventIndex_1,
    contractAddress_2
  )

  protected lazy val duplicateAddresses = AVector(contractAddress_0, contractAddress_0)
}

trait WsServerFixture extends ServerFixture with ScalaFutures {

  override val configValues                        = configPortsValues
  implicit protected lazy val apiConfig: ApiConfig = ApiConfig.load(newConfig)
  implicit protected val timeout: Timeout          = Timeout(Duration.ofSecondsUnsafe(5).asScala)
  implicit protected val system: ActorSystem       = ActorSystem("websocket-server-spec")
  implicit protected val executionContext: ExecutionContext = system.dispatcher
  protected lazy val vertx                                  = Vertx.vertx()
  protected lazy val blockFlowProbe                         = TestProbe()
  protected lazy val (allHandlers, _)                       = TestUtils.createAllHandlersProbe
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
  protected lazy val wsPort: Int = node.config.network.restPort
  protected lazy val wsClient: WsClient =
    WsClient(
      vertx,
      new WebSocketClientOptions()
        .setMaxFrameSize(node.config.network.wsMaxFrameSize)
        .setMaxConnections(maxClientConnections)
    )

  lazy val wsOptions =
    new HttpServerOptions()
      .setMaxWebSocketFrameSize(node.config.network.wsMaxFrameSize)
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
        node.config.network.wsMaxSubscriptionsPerConnection,
        node.config.network.wsMaxContractEventAddresses,
        keepAliveInterval,
        wsOptions
      )
    wsServer.httpServer
      .listen(node.config.network.restPort, apiConfig.networkInterface.getHostAddress)
      .asScala
      .futureValue
    wsServer
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
      .ask(GetSubscriptions)
      .mapTo[WsImmutableSubscriptions]
      .futureValue
      .subscriptions
      .size is 0
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

  implicit protected val wsNotificationParamsReader: Reader[WsNotificationParams] =
    reader[ujson.Value].map[WsNotificationParams] {
      case ujson.Obj(values) =>
        val subscription = Hash.unsafe(Hex.unsafe(values("subscription").str))
        values("result") match {
          case obj: ujson.Obj if obj.value.contains("block") =>
            WsBlockNotificationParams(subscription, read[BlockAndEvents](obj))
          case obj: ujson.Obj if obj.value.contains("unsigned") =>
            WsTxNotificationParams(subscription, read[TransactionTemplate](obj))
          case obj: ujson.Obj if obj.value.contains("contractAddress") =>
            WsContractEventNotificationParams(subscription, read[ContractEvent](obj))
          case obj: ujson.Obj =>
            throw new Exception(s"Unknown WsNotificationParams type with result: $obj")
          case other =>
            throw new Exception(s"Expected ujson.Obj for 'result', got: $other")
        }
      case other =>
        throw new Exception(s"Invalid JSON format for WsNotificationParams: $other")
    }

  implicit protected val wsRequestReader: Reader[WsRequest] =
    reader[ujson.Value].map[WsRequest] { json =>
      fromJsonString(json.render(), node.config.network.wsMaxContractEventAddresses) match {
        case Right(wsRequest) => wsRequest
        case Left(failure)    => throw failure.error
      }
    }
}

trait WsSubscriptionFixture extends WsServerFixture with WsFixture with Eventually {
  protected def getSubscriptions(
      subscriptionHandler: ActorRefT[WsSubscriptionHandler.SubscriptionMsg]
  ): WsImmutableSubscriptions =
    subscriptionHandler
      .ask(GetSubscriptions)
      .mapTo[WsImmutableSubscriptions]
      .futureValue

  protected def flattenParams(
      wsId: WsId,
      paramss: AVector[ContractEventsSubscribeParams]
  ): AVector[(SubscriptionOfConnection, AddressWithIndex)] = {
    assume(paramss.length == paramss.map(_.subscriptionId).toSet.size)
    paramss.flatMap { params =>
      params.addresses.map(addr =>
        SubscriptionOfConnection(wsId, params.subscriptionId) -> AddressWithIndex(
          addr.toBase58,
          params.eventIndex
        )
      )
    }
  }

  protected lazy val tooManyContractAddresses =
    AVector.fill(networkConfig.wsMaxContractEventAddresses + 1) {
      Address.Contract(
        LockupScript.p2c(
          ContractId.generate
        )
      )
    }

  protected lazy val contractEvent = {
    ContractEvent(
      blockHashGen.sample.get,
      txIdGen.sample.get,
      Address.contract(ContractId.hash("foo")),
      EventIndex_0,
      AVector(ValU256(U256.unsafe(5)))
    )
  }

  protected lazy val contractEventByBlockHash: AVector[ContractEventByBlockHash] =
    logStatesFor(AVector(contractAddress_0.contractId -> EventIndex_0)).map {
      case (contractId, logState) =>
        ContractEventByBlockHash.from(LogStateRef(LogStatesId(contractId, 0), 0), logState)
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
}

trait WsBehaviorFixture extends WsServerFixture with Eventually with IntegrationPatience {
  import org.alephium.app.ws.WsSubscriptionHandler._
  import org.alephium.app.ws.WsBehaviorFixture._

  protected def checkWS(
      initBehaviors: AVector[WsStartBehavior],
      nextBehaviors: AVector[WsNextBehavior],
      expectedSubscriptions: Int,
      openWebsocketsCount: Int
  ): Unit = {
    val WsServer(httpServer, eventHandler, subscriptionHandler) = bindAndListen()

    eventually(testSubscriptionHandlerInitialized(subscriptionHandler))
    eventually(testEventHandlerInitialized(eventHandler))

    val probedSockets =
      initBehaviors.map { case WsStartBehavior(startBehavior, _, _) =>
        val clientProbe = TestProbe()
        val ws          = startBehavior(clientProbe).futureValue
        ws -> clientProbe
      }

    initBehaviors.foreach { case WsStartBehavior(_, serverBehavior, clientAssertionOnMsg) =>
      serverBehavior(node.eventBus)
      probedSockets.foreach { case (_, clientProbe) =>
        clientAssertionOnMsg(clientProbe)
      }
    }
    probedSockets.foreach { case (ws, clientProbe) =>
      nextBehaviors.foreach { case WsNextBehavior(behavior, serverBehavior, clientAssertionOnMsg) =>
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
    probedSockets.foreach(_._1.close().futureValue)
    httpServer.close().asScala.mapTo[Unit].futureValue
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
