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

import org.alephium.app.ws.WsParams.{SimpleSubscribeParams, WsSubscriptionId}
import org.alephium.app.ws.WsSubscriptionHandler.SubscriptionOfConnection
import org.alephium.util.{AlephiumSpec, AVector}

class WsSubscriptionsStateSpec extends AlephiumSpec with WsFixture {

  private val consumer_0 = "0"
  private val consumer_1 = "1"
  private val consumer_2 = "2"

  private val wsId_0 = "0"
  private val wsId_1 = "1"

  private lazy val subscriptionOfConnection_0 =
    SubscriptionOfConnection(
      wsId_0,
      contractEventsParams_0.subscriptionId
    )

  private lazy val subscriptionOfConnection_1 =
    SubscriptionOfConnection(
      wsId_0,
      contractEventsParams_1.subscriptionId
    )

  private lazy val subscriptionOfConnection_2 =
    SubscriptionOfConnection(
      wsId_0,
      contractEventsParams_2.subscriptionId
    )

  it should "be idempotent on adding new contract event subscriptions" in {
    val subscriptionsState = WsSubscriptionsState.empty[String]()

    val addr = contractEventsParams_0.toAddressesWithIndex.head
    subscriptionsState.addContractEventSubscriptionForAddress(subscriptionOfConnection_0, addr)
    subscriptionsState.addContractEventSubscriptionForAddress(subscriptionOfConnection_0, addr)

    subscriptionsState.contractSubscriptionMappings is mutable.Map(
      addr -> AVector(subscriptionOfConnection_0)
    )

    subscriptionsState.addContractEventSubscriptionForAddress(subscriptionOfConnection_1, addr)

    subscriptionsState.contractSubscriptionMappings is mutable.Map(
      addr -> AVector(subscriptionOfConnection_0, subscriptionOfConnection_1)
    )
  }

  it should "be idempotent on adding new subscriptions" in {
    val subscriptionsState = WsSubscriptionsState.empty[String]()

    subscriptionsState.addNewSubscription(wsId_0, contractEventsParams_0, consumer_0)
    subscriptionsState.addNewSubscription(wsId_0, contractEventsParams_0, consumer_0)

    subscriptionsState.connections is mutable.Map(
      wsId_0 -> AVector(contractEventsParams_0.subscriptionId -> consumer_0)
    )

    subscriptionsState.contractAddressMappings is mutable.Map(
      subscriptionOfConnection_0 -> contractEventsParams_0.toAddressesWithIndex
    )

    subscriptionsState.contractSubscriptionMappings is mutable.Map.from(
      contractEventsParams_0.toAddressesWithIndex.map(_ -> AVector(subscriptionOfConnection_0))
    )

  }

  it should "get consumers and subscriptions" in {
    val subscriptionsState = WsSubscriptionsState.empty[String]()

    subscriptionsState.addNewSubscription(wsId_0, contractEventsParams_0, consumer_0)
    subscriptionsState.addNewSubscription(wsId_1, contractEventsParams_1, consumer_1)
    subscriptionsState.addNewSubscription(wsId_1, contractEventsParams_2, consumer_2)

    subscriptionsState.getConsumer(wsId_0, contractEventsParams_1.subscriptionId) is None
    subscriptionsState.getSubscriptions(wsId_0) is AVector(contractEventsParams_0.subscriptionId)

    subscriptionsState.getConsumer(wsId_1, contractEventsParams_1.subscriptionId) is Some(
      consumer_1
    )
    subscriptionsState.getConsumers(wsId_1) is AVector(consumer_1, consumer_2)
    subscriptionsState.getSubscriptions(wsId_1) is AVector(
      contractEventsParams_1.subscriptionId,
      contractEventsParams_2.subscriptionId
    )

    subscriptionsState.getConsumers("") is AVector.empty[String]
    subscriptionsState.getSubscriptions("") is AVector.empty[WsSubscriptionId]
  }

  "getting unique subscription ids" should "deduplicate subscription ids for given address" in {
    val subscriptionsState = WsSubscriptionsState.empty[String]()
    subscriptionsState.addNewSubscription(wsId_0, contractEventsParams_0, consumer_0)
    subscriptionsState.addNewSubscription(wsId_1, contractEventsParams_0, consumer_0)

    subscriptionsState.getUniqueSubscriptionIds(
      contractEventsParams_0.addresses.head,
      contractEventsParams_0.eventIndex
    ) is AVector(contractEventsParams_0.subscriptionId)
  }

  "adding simple subscription" should "not affect contract mappings" in {
    val subscriptionsState = WsSubscriptionsState.empty[String]()

    subscriptionsState.addNewSubscription(wsId_0, SimpleSubscribeParams.Block, consumer_0)
    subscriptionsState.addNewSubscription(wsId_0, SimpleSubscribeParams.Tx, consumer_0)

    subscriptionsState.connections is mutable.Map(
      wsId_0 -> AVector(
        SimpleSubscribeParams.Block.subscriptionId -> consumer_0,
        SimpleSubscribeParams.Tx.subscriptionId    -> consumer_0
      )
    )

    subscriptionsState.contractAddressMappings.size is 0
    subscriptionsState.contractSubscriptionMappings.size is 0
  }

  it should "add and remove subscriptions and addresses" in {
    val subscriptionsState = WsSubscriptionsState.empty[String]()

    subscriptionsState.addNewSubscription(wsId_0, contractEventsParams_1, consumer_0)
    subscriptionsState.addNewSubscription(wsId_0, contractEventsParams_2, consumer_0)

    subscriptionsState.connections is mutable.Map(
      wsId_0 -> AVector(
        contractEventsParams_1.subscriptionId -> consumer_0,
        contractEventsParams_2.subscriptionId -> consumer_0
      )
    )

    subscriptionsState.contractAddressMappings is mutable.Map(
      subscriptionOfConnection_1 -> contractEventsParams_1.toAddressesWithIndex,
      subscriptionOfConnection_2 -> contractEventsParams_2.toAddressesWithIndex
    )

    val expectedKeys =
      contractEventsParams_1.toAddressesWithIndex ++ contractEventsParams_2.toAddressesWithIndex
    subscriptionsState.contractSubscriptionMappings.keys.toSet is expectedKeys.toSet

    val subscriptionsSharingAddress =
      AVector(subscriptionOfConnection_1, subscriptionOfConnection_2)
    val sharedAddress = contractEventsParams_2.toAddressesWithIndex.head
    subscriptionsState.contractSubscriptionMappings(sharedAddress) is subscriptionsSharingAddress

    subscriptionsState.removeSubscription(wsId_0, contractEventsParams_1.subscriptionId)

    subscriptionsState.connections is mutable.Map(
      wsId_0 -> AVector(contractEventsParams_2.subscriptionId -> consumer_0)
    )

    subscriptionsState.contractAddressMappings is mutable.Map(
      subscriptionOfConnection_2 -> contractEventsParams_2.toAddressesWithIndex
    )

    subscriptionsState.contractSubscriptionMappings.keys.toSet is contractEventsParams_2.toAddressesWithIndex.toSet
    subscriptionsState.contractSubscriptionMappings.foreachEntry { case (_, subscriptions) =>
      subscriptions.length is 1
      subscriptions.head is subscriptionOfConnection_2
    }
  }

  "removing subscription by address" should "not allow for addresses with 0 subscriptions" in {
    val subscriptionsState = WsSubscriptionsState.empty[String]()
    subscriptionsState.addNewSubscription(wsId_0, contractEventsParams_1, consumer_0)
    contractEventsParams_1.toAddressesWithIndex.map { addressWithIndex =>
      subscriptionsState.removeSubscriptionByAddress(addressWithIndex, subscriptionOfConnection_1)
    }

    subscriptionsState.contractSubscriptionMappings.size is 0
  }

  it should "remove all subscriptions for given connection" in {
    val subscriptionsState = WsSubscriptionsState.empty[String]()

    subscriptionsState.addNewSubscription(wsId_0, contractEventsParams_0, consumer_0)
    subscriptionsState.addNewSubscription(wsId_1, contractEventsParams_1, consumer_1)
    subscriptionsState.addNewSubscription(wsId_1, contractEventsParams_2, consumer_2)

    subscriptionsState.removeAllSubscriptions(wsId_1)

    subscriptionsState.connections is mutable.Map(
      wsId_0 -> AVector(contractEventsParams_0.subscriptionId -> consumer_0)
    )

    subscriptionsState.contractAddressMappings.forall(_._1.wsId == wsId_0) is true
    subscriptionsState.contractSubscriptionMappings.forall(_._2.forall(_.wsId == wsId_0)) is true
  }
}
