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

import org.alephium.api.ApiModelCodec
import org.alephium.api.model.{
  BlockAndEvents,
  BlockEntry,
  ContractEventByBlockHash,
  TransactionTemplate,
  Val
}
import org.alephium.app.ws.WsParams.{WsBlockNotificationParams, WsTxNotificationParams}
import org.alephium.app.ws.WsSubscriptionHandler.{NotificationPublished, SubscriptionMsg}
import org.alephium.flow.handler.AllHandlers.{BlockNotify, TxNotify}
import org.alephium.protocol.config.NetworkConfig
import org.alephium.protocol.model.Address
import org.alephium.util.{ActorRefT, BaseActor, EventBus}

protected[ws] object WsEventHandler extends ApiModelCodec {

  def getSubscribedEventHandler(
      eventBusRef: ActorRefT[EventBus.Message],
      subscriptionHandlerRef: ActorRefT[SubscriptionMsg],
      system: ActorSystem
  )(implicit
      networkConfig: NetworkConfig
  ): ActorRefT[EventBus.Message] = {
    val eventHandlerRef = ActorRefT.build[EventBus.Message](
      system,
      Props(new WsEventHandler(subscriptionHandlerRef))
    )
    eventBusRef.tell(EventBus.Subscribe, eventHandlerRef.ref)
    eventHandlerRef
  }
}

protected[ws] class WsEventHandler(subscriptionHandlerRef: ActorRefT[SubscriptionMsg])(implicit
    val networkConfig: NetworkConfig
) extends BaseActor
    with ApiModelCodec {

  def receive: Receive = {
    case TxNotify(tx, seenAt) =>
      val params = WsTxNotificationParams.from(TransactionTemplate.fromProtocol(tx, seenAt))
      subscriptionHandlerRef ! NotificationPublished(params)
    case BlockNotify(block, height, logStates) =>
      BlockEntry.from(block, height) match {
        case Right(blockEntry) =>
          val contractEvents =
            logStates.map { case (contractId, logState) =>
              val contractAddress = Address.contract(contractId)
              val eventIndex      = logState.index.toInt
              val fields          = logState.fields.map(Val.from)
              ContractEventByBlockHash(logState.txId, contractAddress, eventIndex, fields)
            }
          val params = WsBlockNotificationParams.from(BlockAndEvents(blockEntry, contractEvents))
          subscriptionHandlerRef ! NotificationPublished(params)
        case Left(error) =>
          log.error(error)
      }
  }

}
