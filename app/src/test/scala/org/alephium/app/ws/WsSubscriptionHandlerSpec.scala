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

import java.util.concurrent.TimeUnit

import scala.concurrent.Future
import scala.concurrent.duration.{DurationInt, FiniteDuration}

import akka.actor.ActorSystem
import akka.testkit.TestProbe
import io.vertx.core.Vertx
import org.scalatest.{Assertion, BeforeAndAfterAll}
import org.scalatest.Inside.inside
import org.scalatest.concurrent.{Eventually, ScalaFutures}

import org.alephium.app.ws.WsParams.{SimpleSubscribeParams, WsNotificationParams, WsSubscriptionId}
import org.alephium.app.ws.WsSubscriptionHandler._
import org.alephium.app.ws.WsSubscriptionsState.{ContractEventKey, SubscriptionOfConnection}
import org.alephium.app.ws.WsUtils._
import org.alephium.flow.handler.AllHandlers.{BlockNotify, TxNotify}
import org.alephium.json.Json._
import org.alephium.rpc.model.JsonRPC
import org.alephium.rpc.model.JsonRPC.{Error, Notification, Response}
import org.alephium.util._

class WsSubscriptionHandlerSpec extends AlephiumSpec with BeforeAndAfterAll with ScalaFutures {
  import org.alephium.app.ws.WsBehaviorFixture._

  private lazy val system: ActorSystem = ActorSystem("ws-subscription-handler-spec")

  override def afterAll(): Unit = {
    super.afterAll()
    system.terminate().futureValue
    ()
  }

  it should "connect and subscribe multiple ws clients to multiple events" in new WsBehaviorFixture {
    val subscriptionIdSet =
      Set(
        SimpleSubscribeParams.Block.subscriptionId,
        SimpleSubscribeParams.Tx.subscriptionId,
        params_addr_01_eventIndex_0.subscriptionId
      )
    def subscribingRequestResponseBehavior(clientProbe: TestProbe): Future[ClientWs] = {
      for {
        ws                        <- wsClient.connect(wsPort)(ntf => clientProbe.ref ! ntf)(_ => ())
        blockSubscriptionResponse <- ws.subscribeToBlock(corId_0)
        txSubscriptionResponse    <- ws.subscribeToTx(corId_1)
        contractEventsSubscriptionResponse <- ws.subscribeToContractEvents(
          corId_2,
          params_addr_01_eventIndex_0.addresses,
          params_addr_01_eventIndex_0.eventIndex
        )
      } yield {
        inside(blockSubscriptionResponse) { case JsonRPC.Response.Success(result, id) =>
          id is corId_0
          result is ujson.Str(SimpleSubscribeParams.Block.subscriptionId.toHexString)
        }
        inside(txSubscriptionResponse) { case JsonRPC.Response.Success(result, id) =>
          id is corId_1
          result is ujson.Str(SimpleSubscribeParams.Tx.subscriptionId.toHexString)
        }
        inside(contractEventsSubscriptionResponse) { case JsonRPC.Response.Success(result, id) =>
          id is corId_2
          result is ujson.Str(params_addr_01_eventIndex_0.subscriptionId.toHexString)
        }
        ws
      }
    }

    def serverBehavior(eventBusRef: ActorRefT[EventBus.Message]): Unit = {
      eventBusRef ! BlockNotify(
        blockGen.sample.get,
        height = 0,
        logStatesFor(AVector(contractAddress_0.contractId -> EventIndex_0))
      )
      eventBusRef ! TxNotify(transactionGen().sample.get.toTemplate, TimeStamp.now())
    }

    def assertValidNotification(
        wsEither: Either[Throwable, ClientWs],
        clientProbe: TestProbe
    ): Assertion = {
      wsEither.left.foreach(ex => fail(s"Expected Right, but got Left with exception: $ex"))
      val subscriptionIds =
        AVector.fill(3)(clientProbe.expectMsgType[Notification]).map { notification =>
          notification.method is WsMethod.SubscriptionMethod
          read[WsNotificationParams](notification.params).subscription
        }
      clientProbe.expectNoMessage(50.millis)
      subscriptionIds.toSet is subscriptionIdSet
    }

    checkWS(
      initBehaviors = AVector.fill(3)(
        WsStartBehavior(subscribingRequestResponseBehavior, serverBehavior, assertValidNotification)
      ),
      nextBehaviors = AVector.empty,
      expectedSubscriptions = 3 * 3, // 3 clients, 3 subscriptions each
      openWebsocketsCount = 3
    )
  }

  it should "not spin ws connections over limit" in new WsBehaviorFixture {
    override def maxServerConnections: Int = 0
    val wsStartBehaviors =
      AVector(
        WsStartBehavior(
          _ => wsClient.connect(wsPort)(_ => ())(_ => ()),
          _ => (),
          (wsEither, _) => wsEither.leftValue.getMessage.contains("WebSocket upgrade failure")
        )
      )
    checkWS(
      initBehaviors = wsStartBehaviors,
      nextBehaviors = AVector.empty,
      expectedSubscriptions = 0,
      openWebsocketsCount = 0
    )
  }

  it should "not allow for more subscriptions per ws client than limit" in new WsBehaviorFixture {
    def subscribingBehavior(clientProbe: TestProbe): Future[ClientWs] = {
      for {
        ws <- wsClient.connect(wsPort)(ntf => clientProbe.ref ! ntf)(_ => ())
        successfulSubscriptions <- Future.sequence(
          AVector
            .tabulate(node.config.network.wsMaxSubscriptionsPerConnection) { index =>
              ws.subscribeToContractEvents(
                index.toLong,
                params_addr_01_eventIndex_0.addresses,
                eventIndex = Some(index)
              )
            }
            .toIterable
        )
        rejectedSubscription <- ws.subscribeToContractEvents(
          50L,
          params_addr_12_eventIndex_1.addresses,
          params_addr_12_eventIndex_1.eventIndex
        )
      } yield {
        inside(successfulSubscriptions) { case responses =>
          responses.foreach {
            case JsonRPC.Response.Success(_, _) =>
            case JsonRPC.Response.Failure(error, _) =>
              fail(error.getMessage)
          }
        }
        inside(rejectedSubscription) { case JsonRPC.Response.Failure(error, id) =>
          error is WsError.subscriptionLimitExceeded(
            node.config.network.wsMaxSubscriptionsPerConnection
          )
          id is Some(50L)
        }
        ws
      }
    }

    val wsSpec = WsStartBehavior(
      subscribingBehavior,
      _ => (),
      (_, _) => true is true
    )
    checkWS(
      initBehaviors = AVector.fill(1)(wsSpec),
      nextBehaviors = AVector.empty,
      expectedSubscriptions = 50,
      openWebsocketsCount = 1
    )
  }

  it should "handle invalid messages with error" in new WsBehaviorFixture {
    def invalidMessageBehavior(clientProbe: TestProbe): Future[ClientWs] = {
      for {
        vertxWs <- wsClient.underlying.connect(wsPort, "127.0.0.1", "/ws").asScala
        ws = ClientWs(vertxWs, _ => (), _ => ())
        // using underlying ws to test unexpected messages, WsClient does not allow that
        _ = vertxWs.textMessageHandler(clientProbe.ref ! _)
        _ <- vertxWs.writeTextMessage("invalid_msg").asScala
      } yield ws
    }

    def assertParsingError(
        wsEither: Either[Throwable, ClientWs],
        clientProbe: TestProbe
    ): Assertion = {
      wsEither.left.foreach(ex => fail(s"Expected Right, but got Left with exception: $ex"))
      inside(read[Response.Failure](clientProbe.expectMsgClass(classOf[String]))) {
        case JsonRPC.Response.Failure(error, _) =>
          error.code is Error.ParseErrorCode
      }
    }

    def invalidSubscriptionParamsBehavior(wsEither: Either[Throwable, ClientWs]): Unit = {
      wsEither match {
        case Right(clientWs) =>
          clientWs.underlying
            .writeTextMessage(write(WsRequest.subscribe(corId_0, SimpleSubscribeParams(""))))
            .asScala
            .mapTo[Unit]
            .futureValue
        case Left(ex) =>
          fail(s"Expected Right, but got Left with exception: $ex")
      }
    }

    def assertInvalidSubscription(clientProbe: TestProbe): Assertion = {
      inside(read[Response](clientProbe.expectMsgClass(classOf[String]))) {
        case JsonRPC.Response.Success(_, _) =>
          fail("Should not receive success response")
        case JsonRPC.Response.Failure(error, _) =>
          error.code is Error.InvalidParamsCode
      }
    }

    def assertInvalidUnsubscription(clientProbe: TestProbe): Assertion = {
      inside(read[Response](clientProbe.expectMsgClass(classOf[String]))) {
        case JsonRPC.Response.Success(_, _) =>
          fail("Should not receive success response")
        case JsonRPC.Response.Failure(error, _) =>
          error.code is Error.InvalidParamsCode
      }
    }

    def invalidUnsubscriptionParamsBehavior(wsEither: Either[Throwable, ClientWs]): Unit = {
      val invalidUnsubscribeReq = ujson
        .Obj(
          "method"  -> WsMethod.UnsubscribeMethod,
          "params"  -> ujson.Arr("invalidSubscriptionId"),
          "id"      -> 0,
          "jsonrpc" -> "2.0"
        )
      wsEither match {
        case Right(clientWs) =>
          clientWs.underlying
            .writeTextMessage(invalidUnsubscribeReq.render())
            .asScala
            .mapTo[Unit]
            .futureValue
        case Left(ex) =>
          fail(s"Expected Right, but got Left with exception: $ex")
      }
    }

    val wsInitBehavior = WsStartBehavior(invalidMessageBehavior, _ => (), assertParsingError)
    val wsNextBehaviors =
      AVector(
        WsNextBehavior(invalidSubscriptionParamsBehavior, _ => (), assertInvalidSubscription),
        WsNextBehavior(invalidUnsubscriptionParamsBehavior, _ => (), assertInvalidUnsubscription)
      )
    checkWS(
      initBehaviors = AVector.fill(1)(wsInitBehavior),
      nextBehaviors = wsNextBehaviors,
      expectedSubscriptions = 0,
      openWebsocketsCount = 1
    )
  }

  it should "subscribe/unsubscribe from block, tx and contract events from multiple addresses of different event indexes" in new WsBehaviorFixture {
    def subscribingBehavior(clientProbe: TestProbe): Future[ClientWs] = {
      for {
        ws                        <- wsClient.connect(wsPort)(ntf => clientProbe.ref ! ntf)(_ => ())
        blockSubscriptionResponse <- ws.subscribeToBlock(corId_0)
        txSubscriptionResponse    <- ws.subscribeToTx(corId_1)
        contractEventsSubscriptionResponse_0 <- ws.subscribeToContractEvents(
          corId_2,
          params_addr_01_eventIndex_0.addresses,
          params_addr_01_eventIndex_0.eventIndex
        )
        contractEventsSubscriptionResponse_1 <- ws.subscribeToContractEvents(
          corId_3,
          params_addr_12_eventIndex_1.addresses,
          params_addr_12_eventIndex_1.eventIndex
        )
        contractEventsSubscriptionResponse_2 <- ws.subscribeToContractEvents(
          corId_4,
          params_addr_3_unfiltered.addresses,
          params_addr_3_unfiltered.eventIndex
        )
      } yield {
        inside(blockSubscriptionResponse) { case JsonRPC.Response.Success(result, id) =>
          id is corId_0
          result is ujson.Str(SimpleSubscribeParams.Block.subscriptionId.toHexString)
        }
        inside(txSubscriptionResponse) { case JsonRPC.Response.Success(result, id) =>
          id is corId_1
          result is ujson.Str(SimpleSubscribeParams.Tx.subscriptionId.toHexString)
        }
        inside(contractEventsSubscriptionResponse_0) { case JsonRPC.Response.Success(result, id) =>
          id is corId_2
          result is ujson.Str(params_addr_01_eventIndex_0.subscriptionId.toHexString)
        }
        inside(contractEventsSubscriptionResponse_1) { case JsonRPC.Response.Success(result, id) =>
          id is corId_3
          result is ujson.Str(params_addr_12_eventIndex_1.subscriptionId.toHexString)
        }
        inside(contractEventsSubscriptionResponse_2) { case JsonRPC.Response.Success(result, id) =>
          id is corId_4
          result is ujson.Str(params_addr_3_unfiltered.subscriptionId.toHexString)
        }
        ws
      }
    }

    def assertCorrectNotificationResponse(
        wsEither: Either[Throwable, ClientWs],
        clientProbe: TestProbe
    ): Assertion = {
      wsEither.left.foreach(ex => fail(s"Expected Right, but got Left with exception: $ex"))
      val notifications = AVector.fill(6)(clientProbe.expectMsgType[Notification])
      val params        = notifications.map(n => read[WsNotificationParams](n.params))
      // notifications for 1 tx, 1 block and 4 contract events
      params.map(_.subscription).length is 6
      // notifications come for 1 tx, 1 block and 3 unique contract event subscription Ids
      params.map(_.subscription).distinct.length is 5
    }

    def testResponse(correlationId: Long)(response: Response): Unit = {
      inside(response) { case JsonRPC.Response.Success(result, id) =>
        result is ujson.True
        id is correlationId
      }
      ()
    }

    def unsubscribingBehavior(wsEither: Either[Throwable, ClientWs]): Unit = {
      val ws = wsEither match {
        case Right(clientWs) =>
          clientWs
        case Left(ex) =>
          fail(s"Expected Right, but got Left with exception: $ex")
      }
      (for {
        _ <- ws.unsubscribeFromBlock(corId_5).map(testResponse(corId_5))
        _ <- ws.unsubscribeFromTx(corId_6).map(testResponse(corId_6))
        _ <- ws
          .unsubscribeFromContractEvents(corId_7, params_addr_01_eventIndex_0.subscriptionId)
          .map(testResponse(corId_7))
        _ <- ws
          .unsubscribeFromContractEvents(corId_8, params_addr_12_eventIndex_1.subscriptionId)
          .map(testResponse(corId_8))
        _ <- ws
          .unsubscribeFromContractEvents(corId_9, params_addr_3_unfiltered.subscriptionId)
          .map(testResponse(corId_9))
      } yield ()).futureValue
    }

    val wsInitBehavior = WsStartBehavior(
      subscribingBehavior,
      eventBusRef => {
        eventBusRef ! BlockNotify(
          blockGen.sample.get,
          height = 0,
          logStatesFor(
            AVector(
              contractAddress_0.contractId -> EventIndex_0,
              contractAddress_1.contractId -> EventIndex_0,
              contractAddress_2.contractId -> EventIndex_1,
              contractAddress_3.contractId -> 1234 // contractAddress_3 is not filtered by eventIndex
            )
          )
        )
        eventBusRef ! TxNotify(transactionGen().sample.get.toTemplate, TimeStamp.now())
      },
      assertCorrectNotificationResponse
    )
    val wsNextBehavior = WsNextBehavior(
      unsubscribingBehavior,
      eventBusRef => {
        eventBusRef ! BlockNotify(
          blockGen.sample.get,
          height = 1,
          logStatesFor(AVector(contractAddress_0.contractId -> EventIndex_0))
        )
        eventBusRef ! TxNotify(transactionGen().sample.get.toTemplate, TimeStamp.now())
      },
      _.expectNoMessage(50.millis)
    )
    checkWS(
      initBehaviors = AVector.fill(1)(wsInitBehavior),
      nextBehaviors = AVector.fill(1)(wsNextBehavior),
      expectedSubscriptions = 0, // unsubscribed
      openWebsocketsCount = 1    // unsubscribed but not disconnected
    )
  }

  it should "support multiple subscriptions of multiple clients to multiple addresses of different event index" in new WsSubscriptionFixture
    with Eventually {
    val subscriptionHandler =
      WsSubscriptionHandler.apply(
        Vertx.vertx(),
        system,
        config.network.wsMaxConnections,
        config.network.wsMaxSubscriptionsPerConnection,
        config.network.wsMaxContractEventAddresses,
        FiniteDuration(config.network.wsPingFrequency.millis, TimeUnit.MILLISECONDS)
      )
    eventually(testSubscriptionHandlerInitialized(subscriptionHandler))

    val websockets    = AVector(dummyServerWs("dummy_0"), dummyServerWs("dummy_1"))
    var correlationId = 0L
    val contractEventParams =
      AVector(
        params_addr_01_eventIndex_0,
        params_addr_12_eventIndex_1,
        params_addr_2_eventIndex_1,
        params_addr_3_unfiltered
      )

    val subscriptionRequests: AVector[(SubscriptionOfConnection, ContractEventKey)] =
      websockets.flatMap { ws =>
        contractEventParams.foreach { params =>
          subscriptionHandler ! Subscribe(correlationId, ws, params)
          correlationId += 1
        }
        flattenParams(ws.textHandlerID(), contractEventParams)
      }

    val expectedSubscriptionsByAddress =
      AVector.from(
        subscriptionRequests
          .groupBy(_._2)
          .view
          .mapValues(_.map(_._1))
      )

    val expectedAddressesBySubscriptionId =
      AVector.from(
        subscriptionRequests
          .groupBy(_._1)
          .view
          .mapValues(_.map(_._2))
      )
    eventually {
      val response = getSubscriptions(subscriptionHandler)
      response.subscriptionsByContractKey is expectedSubscriptionsByAddress.iterator.toMap
      response.contractKeysBySubscription is expectedAddressesBySubscriptionId.iterator.toMap
    }

    // it should be idempotent for subscriptions
    websockets.foreach { ws =>
      contractEventParams.foreach { params =>
        subscriptionHandler ! Subscribe(correlationId, ws, params)
        correlationId += 1
      }
    }
    eventually {
      val response = getSubscriptions(subscriptionHandler)
      response.subscriptionsByContractKey is expectedSubscriptionsByAddress.iterator.toMap
      response.contractKeysBySubscription is expectedAddressesBySubscriptionId.iterator.toMap
    }

    websockets.foreach { ws =>
      contractEventParams.foreach { params =>
        subscriptionHandler ! Unsubscribe(
          correlationId.toLong,
          ws,
          params.subscriptionId
        )
        correlationId += 1
      }
      eventually {
        contractEventParams.foreach { params =>
          assertConnectedButNotSubscribed(
            ws.textHandlerID(),
            params.subscriptionId,
            subscriptionHandler
          )
        }
      }
    }
    eventually {
      val response = getSubscriptions(subscriptionHandler)
      response.subscriptionsByContractKey is Map
        .empty[ContractEventKey, AVector[SubscriptionOfConnection]]
      response.contractKeysBySubscription is Map
        .empty[SubscriptionOfConnection, AVector[ContractEventKey]]
    }

    websockets.foreach { ws =>
      subscriptionHandler ! Disconnect(ws.textHandlerID())
      eventually(assertNotConnected(ws.textHandlerID(), subscriptionHandler))
    }
  }

  it should "support subscription, unsubscription and disconnection" in new WsSubscriptionFixture
    with Eventually {
    val subscriptionHandler =
      WsSubscriptionHandler.apply(
        Vertx.vertx(),
        system,
        config.network.wsMaxConnections,
        config.network.wsMaxSubscriptionsPerConnection,
        config.network.wsMaxContractEventAddresses,
        FiniteDuration(config.network.wsPingFrequency.millis, TimeUnit.MILLISECONDS)
      )
    eventually(testSubscriptionHandlerInitialized(subscriptionHandler))

    val ws = dummyServerWs("dummy")

    def assertSimpleSubscription(
        subscriptionId: WsSubscriptionId,
        subscriptionHandler: ActorRefT[WsSubscriptionHandler.SubscriptionMsg]
    ): Assertion = {
      val response = getSubscriptions(subscriptionHandler)
      response.connections
        .find(_._1 == ws.textHandlerID())
        .tapEach(_._2.length is 1)
        .exists(_._2.filter(_._1 == subscriptionId).length == 1) is true
    }

    AVector(
      SimpleSubscribeParams.Block,
      SimpleSubscribeParams.Tx,
      params_addr_01_eventIndex_0,
      params_addr_3_unfiltered
    ).fold(
      0L
    ) { case (correlationId, params) =>
      subscriptionHandler ! Subscribe(correlationId, ws, params)
      eventually {
        assertSimpleSubscription(
          params.subscriptionId,
          subscriptionHandler
        )
      }

      // it should be idempotent for subscriptions
      subscriptionHandler ! Subscribe(correlationId + 1, ws, params)
      eventually {
        assertSimpleSubscription(
          params.subscriptionId,
          subscriptionHandler
        )
      }

      subscriptionHandler ! Unsubscribe(correlationId + 2, ws, params.subscriptionId)
      eventually {
        assertConnectedButNotSubscribed(
          ws.textHandlerID(),
          params.subscriptionId,
          subscriptionHandler
        )
      }
      correlationId + 3
    }

    subscriptionHandler ! Disconnect(ws.textHandlerID())
    eventually(assertNotConnected(ws.textHandlerID(), subscriptionHandler))
  }
}
