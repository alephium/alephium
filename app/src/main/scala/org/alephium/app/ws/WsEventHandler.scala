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

import akka.actor.{ActorSystem, Props}
import io.vertx.core.eventbus.{EventBus => VertxEventBus}

import org.alephium.api.ApiModelCodec
import org.alephium.api.model.{
  BlockAndEvents,
  BlockEntry,
  ContractEventByBlockHash,
  TransactionTemplate,
  Val
}
import org.alephium.app.ws.WsEventHandler.buildJsonRpcNotification
import org.alephium.app.ws.WsParams.{
  SubscribeParams,
  WsBlockNotificationParams,
  WsNotificationParams,
  WsTxNotificationParams
}
import org.alephium.flow.handler.AllHandlers.{BlockNotify, TxNotify}
import org.alephium.json.Json.{write, writeJs}
import org.alephium.protocol.config.NetworkConfig
import org.alephium.protocol.model.Address
import org.alephium.rpc.model.JsonRPC.Notification
import org.alephium.util.{ActorRefT, BaseActor, EventBus}

protected[ws] object WsEventHandler extends ApiModelCodec {

  def getSubscribedEventHandler(
      vertxEventBus: VertxEventBus,
      eventBusRef: ActorRefT[EventBus.Message],
      system: ActorSystem
  )(implicit
      networkConfig: NetworkConfig
  ): ActorRefT[EventBus.Message] = {
    val eventHandlerRef =
      ActorRefT.build[EventBus.Message](system, Props(new WsEventHandler(vertxEventBus)))
    eventBusRef.tell(EventBus.Subscribe, eventHandlerRef.ref)
    eventHandlerRef
  }

  def buildJsonRpcNotification(params: WsNotificationParams): Notification = {
    Notification(
      WsMethod.SubscriptionMethod,
      writeJs(params)
    )
  }
}

protected[ws] class WsEventHandler(vertxEventBus: VertxEventBus)(implicit
    val networkConfig: NetworkConfig
) extends BaseActor
    with ApiModelCodec {

  def receive: Receive = {
    case TxNotify(tx, seenAt) =>
      val params =
        WsTxNotificationParams(
          SubscribeParams.Tx.subscriptionId,
          TransactionTemplate.fromProtocol(tx, seenAt)
        )
      val _ =
        vertxEventBus.publish(params.subscription, write(buildJsonRpcNotification(params)))

    case BlockNotify(block, height, logStates) =>
      BlockEntry.from(block, height) match {
        case Right(blockEntry) =>
          val contractEvents =
            logStates.map { case (contractId, logState) =>
              ContractEventByBlockHash(
                logState.txId,
                Address.contract(contractId),
                logState.index.toInt,
                logState.fields.map(Val.from)
              )
            }
          val params = WsBlockNotificationParams(
            SubscribeParams.Block.subscriptionId,
            BlockAndEvents(blockEntry, contractEvents)
          )
          val _ =
            vertxEventBus.publish(params.subscription, write(buildJsonRpcNotification(params)))
        case Left(error) =>
          log.error(error)
      }
  }
}
