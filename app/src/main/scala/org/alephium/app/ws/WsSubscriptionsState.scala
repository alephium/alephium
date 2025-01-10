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

import scala.collection.mutable
import scala.reflect.ClassTag

import org.alephium.app.ws.WsParams.{
  ContractEventsSubscribeParams,
  WsEventIndex,
  WsId,
  WsSubscriptionId,
  WsSubscriptionParams
}
import org.alephium.app.ws.WsSubscriptionHandler.{AddressWithIndex, SubscriptionOfConnection}
import org.alephium.protocol.model.Address
import org.alephium.util.AVector

/** WsSubscriptionHandler actor state (mutable for performance reasons) with many-to-many
  * relationship between subscription and contract event addresses
  * @param connections
  *   client who unsubscribes from all subscriptions is connected but not subscribed to any events
  * @param contractSubscriptionMappings
  *   index by address
  * @param contractAddressMappings
  *   index by subscription id
  */
final case class WsSubscriptionsState[C: ClassTag](
    connections: mutable.Map[WsId, AVector[(WsSubscriptionId, C)]],
    contractSubscriptionMappings: mutable.Map[AddressWithIndex, AVector[SubscriptionOfConnection]],
    contractAddressMappings: mutable.Map[SubscriptionOfConnection, AVector[AddressWithIndex]]
) {

  def getConsumer(wsId: WsId, subscriptionId: WsSubscriptionId): Option[C] = {
    connections
      .get(wsId)
      .flatMap(_.collectFirst {
        case (sid, consumer) if sid == subscriptionId => Option(consumer)
        case _                                        => None
      })
  }

  def getConsumers(wsId: WsId): AVector[C] =
    connections.get(wsId: WsId).map(_.map(_._2)).getOrElse(AVector.empty[C])

  def getSubscriptions(wsId: WsId): AVector[WsSubscriptionId] =
    connections.get(wsId: WsId).map(_.map(_._1)).getOrElse(AVector.empty)

  def getUniqueSubscriptionIds(
      contractAddress: Address.Contract,
      eventIndex: WsEventIndex
  ): AVector[WsSubscriptionId] =
    contractSubscriptionMappings
      .get(AddressWithIndex(contractAddress.toBase58, eventIndex))
      .map(_.map(_.subscriptionId).distinct)
      .getOrElse(AVector.empty)

  protected[ws] def addContractEventSubscriptionForAddress(
      subscriptionOfConnection: SubscriptionOfConnection,
      addressWithIndex: AddressWithIndex
  ): Option[AVector[SubscriptionOfConnection]] =
    contractSubscriptionMappings.updateWith(addressWithIndex) {
      case Some(ss) if ss.contains(subscriptionOfConnection) =>
        // should never happen as we test for it at SubscriptionHandler
        Some(ss)
      case None                => Some(AVector(subscriptionOfConnection))
      case Some(subscriptions) => Some(subscriptions :+ subscriptionOfConnection)
    }

  def addNewSubscription(
      wsId: WsId,
      params: WsSubscriptionParams,
      consumer: C
  ): Unit = {
    val subscriptionId = params.subscriptionId
    connections.updateWith(wsId) {
      case Some(ss) if ss.exists(_._1 == subscriptionId) =>
        Some(ss)
      case Some(ss) =>
        Some(ss :+ (subscriptionId -> consumer))
      case None =>
        Some(AVector(subscriptionId -> consumer))
    }
    params match {
      case contractParams: ContractEventsSubscribeParams =>
        val subscriptionOfConnection = SubscriptionOfConnection(wsId, subscriptionId)
        contractAddressMappings.put(
          subscriptionOfConnection,
          contractParams.addresses.map { address =>
            val addressWithIndex = AddressWithIndex(address.toBase58, contractParams.eventIndex)
            addContractEventSubscriptionForAddress(subscriptionOfConnection, addressWithIndex)
            addressWithIndex
          }
        )
        ()
      case _ =>
    }
  }

  protected[ws] def removeSubscriptionByAddress(
      addressWithIndex: AddressWithIndex,
      subscriptionOfConnection: SubscriptionOfConnection
  ): Option[AVector[SubscriptionOfConnection]] =
    contractSubscriptionMappings.updateWith(addressWithIndex) {
      case None     => None
      case Some(ss) => Option(ss.filterNot(_ == subscriptionOfConnection)).filter(_.nonEmpty)
    }

  def removeSubscription(wsId: WsId, subscriptionId: WsSubscriptionId): Unit = {
    connections.updateWith(wsId)(_.map(_.filterNot(_._1 == subscriptionId)))

    val subscriptionOfConnection = SubscriptionOfConnection(wsId, subscriptionId)
    contractAddressMappings
      .remove(subscriptionOfConnection)
      .foreach { addressesWithIndex =>
        addressesWithIndex.foreach(removeSubscriptionByAddress(_, subscriptionOfConnection))
      }
  }

  def removeAllSubscriptions(wsId: WsId): Unit = {
    connections.remove(wsId)

    AVector.from(contractSubscriptionMappings.keysIterator).foreach { addressWithIndex =>
      contractSubscriptionMappings.updateWith(addressWithIndex) {
        case Some(ss) => Option(ss.filterNot(_.wsId == wsId)).filter(_.nonEmpty)
        case None     => None
      }
    }
    AVector.from(contractAddressMappings.keysIterator).foreach {
      case subscriptionOfConnection if subscriptionOfConnection.wsId == wsId =>
        contractAddressMappings.remove(subscriptionOfConnection)
      case _ =>
    }
  }
}

object WsSubscriptionsState {
  def empty[C: ClassTag](): WsSubscriptionsState[C] =
    WsSubscriptionsState[C](
      mutable.Map.empty[WsId, AVector[(WsSubscriptionId, C)]],
      mutable.Map.empty[AddressWithIndex, AVector[SubscriptionOfConnection]],
      mutable.Map.empty[SubscriptionOfConnection, AVector[AddressWithIndex]]
    )
}
