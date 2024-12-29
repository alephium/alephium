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
import io.vertx.core.http.{HttpServerOptions, WebSocketClientOptions}
import org.scalacheck.Gen
import org.scalatest.Assertion
import org.scalatest.concurrent.{Eventually, IntegrationPatience, ScalaFutures}
import sttp.tapir.server.vertx.VertxFutureServerInterpreter._

import org.alephium.api.ApiModelCodec
import org.alephium.api.model.{BlockAndEvents, ContractEvent, TransactionTemplate}
import org.alephium.app.{ApiConfig, ServerFixture}
import org.alephium.app.ServerFixture.NodeDummy
import org.alephium.app.ws.WsParams.{
  ContractEventsSubscribeParams,
  WsBlockNotificationParams,
  WsContractNotificationParams,
  WsId,
  WsNotificationParams,
  WsSubscriptionId,
  WsTxNotificationParams
}
import org.alephium.app.ws.WsSubscriptionHandler.{
  GetSubscriptions,
  SubscriptionMsg,
  SubscriptionsResponse
}
import org.alephium.flow.handler.TestUtils
import org.alephium.json.Json.{read, reader, Reader}
import org.alephium.protocol.model.{ContractId, Transaction}
import org.alephium.protocol.model.Address
import org.alephium.protocol.vm.{LogState, Val}
import org.alephium.protocol.vm.LockupScript
import org.alephium.util._

trait WsSpec extends AlephiumSpec with ApiModelCodec {
  val EventIndex_0 = 0
  val EventIndex_1 = 1

  lazy val contractAddress_0: Address.Contract = Address.Contract(
    LockupScript.p2c(
      ContractId.zero
    )
  )
  lazy val contractAddress_1: Address.Contract = Address.Contract(
    LockupScript.p2c(
      ContractId.generate
    )
  )
  lazy val contractAddress_2: Address.Contract = Address.Contract(
    LockupScript.p2c(
      ContractId.generate
    )
  )

  val contractEventsParams_0 = ContractEventsSubscribeParams.from(
    EventIndex_0,
    AVector(contractAddress_0, contractAddress_1)
  )
  val contractEventsParams_1 = ContractEventsSubscribeParams.from(
    EventIndex_1,
    AVector(contractAddress_1, contractAddress_2)
  )
  val contractEventsParams_2 = ContractEventsSubscribeParams.from(
    EventIndex_1,
    AVector(contractAddress_2)
  )

  def logStatesFor(
      contractIdsWithEventIndex: AVector[(ContractId, Int)],
      txGen: => Gen[Transaction]
  ): AVector[(ContractId, LogState)] = {
    contractIdsWithEventIndex.map { case (contractId, eventIndex) =>
      contractId -> LogState(
        txGen.sample.get.id,
        eventIndex.toByte,
        AVector(Val.U256(U256.unsafe(1)))
      )
    }
  }

  implicit val wsNotificationParamsReader: Reader[WsNotificationParams] =
    reader[ujson.Value].map[WsNotificationParams] {
      case ujson.Obj(values) =>
        val subscription = values("subscription").str
        values("result") match {
          case obj: ujson.Obj if obj.value.contains("block") =>
            WsBlockNotificationParams(subscription, read[BlockAndEvents](obj))
          case obj: ujson.Obj if obj.value.contains("unsigned") =>
            WsTxNotificationParams(subscription, read[TransactionTemplate](obj))
          case obj: ujson.Obj if obj.value.contains("contractAddress") =>
            WsContractNotificationParams(subscription, read[ContractEvent](obj))
          case obj: ujson.Obj =>
            throw new Exception(s"Unknown WsNotificationParams type with result: $obj")
          case other =>
            throw new Exception(s"Expected ujson.Obj for 'result', got: $other")
        }
      case other =>
        throw new Exception(s"Invalid JSON format for WsNotificationParams: $other")
    }
}

trait WsServerFixture extends ServerFixture with ScalaFutures {

  implicit lazy val apiConfig: ApiConfig          = ApiConfig.load(newConfig)
  implicit val timeout: Timeout                   = Timeout(Duration.ofSecondsUnsafe(5).asScala)
  override val configValues                       = configPortsValues
  implicit val system: ActorSystem                = ActorSystem("websocket-server-spec")
  implicit val executionContext: ExecutionContext = system.dispatcher
  lazy val vertx                                  = Vertx.vertx()
  lazy val blockFlowProbe                         = TestProbe()
  lazy val (allHandlers, _)                       = TestUtils.createAllHandlersProbe
  lazy val node = new NodeDummy(
    dummyIntraCliqueInfo,
    dummyNeighborPeers,
    dummyBlock,
    blockFlowProbe.ref,
    allHandlers,
    dummyTx,
    dummyContract,
    storages
  )
  lazy val wsPort: Int = node.config.network.restPort
  lazy val wsClient: WsClient =
    WsClient(
      vertx,
      new WebSocketClientOptions()
        .setMaxFrameSize(apiConfig.maxWebSocketFrameSize)
        .setMaxConnections(maxClientConnections)
    )

  lazy val wsOptions =
    new HttpServerOptions()
      .setMaxWebSocketFrameSize(1024 * 1024)
      .setRegisterWebSocketWriteHandlers(true)

  def maxServerConnections: Int = 10
  def maxClientConnections: Int = 500

  def bindAndListen(): WsServer = {
    val wsServer =
      WsServer(
        system,
        node,
        maxServerConnections,
        wsOptions
      )
    wsServer.httpServer
      .listen(node.config.network.restPort, apiConfig.networkInterface.getHostAddress)
      .asScala
      .futureValue
    wsServer
  }

  // scalastyle:off regex
  def measureTime[T](operationName: String)(operation: => T): T = {
    val startTime      = System.currentTimeMillis()
    val result         = operation
    val endTime        = System.currentTimeMillis()
    val durationMillis = endTime - startTime
    println(s"It took $durationMillis ms to execute $operationName")
    result
  }
  // scalastyle:on regex

  def dummyServerWs(id: String): ServerWsLike = new ServerWsLike {
    override def textHandlerID(): WsId                                     = id
    override def isClosed: Boolean                                         = false
    override def reject(statusCode: Int): Unit                             = ()
    override def closeHandler(handler: () => Unit): ServerWsLike           = this
    override def textMessageHandler(handler: String => Unit): ServerWsLike = this
    override def writeTextMessage(msg: String): Future[Unit]               = Future.successful(())
  }

  def testSubscriptionHandlerInitialized(
      subscriptionHandler: ActorRefT[SubscriptionMsg]
  ): Assertion = {
    subscriptionHandler
      .ask(GetSubscriptions)
      .mapTo[SubscriptionsResponse]
      .futureValue
      .subscriptions
      .size is 0
  }

  def testEventHandlerInitialized(eventHandler: ActorRefT[EventBus.Message]): Assertion = {
    node.eventBus
      .ask(EventBus.ListSubscribers)
      .mapTo[EventBus.Subscribers]
      .futureValue
      .value
      .contains(eventHandler.ref) is true
  }

}

trait WsSubscriptionFixture extends WsServerFixture with Eventually {
  def getSubscriptions(
      subscriptionHandler: ActorRefT[WsSubscriptionHandler.SubscriptionMsg]
  ): SubscriptionsResponse =
    subscriptionHandler
      .ask(GetSubscriptions)
      .mapTo[SubscriptionsResponse]
      .futureValue

  def assertConnectedButNotSubscribed(
      wsId: WsId,
      subscriptionId: WsSubscriptionId,
      subscriptionHandler: ActorRefT[WsSubscriptionHandler.SubscriptionMsg]
  ): Assertion = {
    getSubscriptions(subscriptionHandler).subscriptions
      .find(_._1 == wsId)
      .exists(_._2.filter(_._1 == subscriptionId).length == 0) is true
  }

  def assertNotConnected(
      wsId: WsId,
      subscriptionHandler: ActorRefT[WsSubscriptionHandler.SubscriptionMsg]
  ): Assertion = {
    !getSubscriptions(subscriptionHandler).subscriptions.exists(_._1 == wsId) is true
  }

}

trait WsBehaviorFixture extends WsServerFixture with Eventually with IntegrationPatience {
  import org.alephium.app.ws.WsSubscriptionHandler._
  import org.alephium.app.ws.WsBehaviorFixture._

  def checkWS(
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
        .mapTo[SubscriptionsResponse]
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
