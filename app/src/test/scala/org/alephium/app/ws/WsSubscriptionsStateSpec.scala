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
import org.alephium.app.ws.WsSubscriptionsState.{buildContractEventKeys, SubscriptionOfConnection}
import org.alephium.util.{AlephiumSpec, AVector}

class WsSubscriptionsStateSpec extends AlephiumSpec with WsFixture {

  private val consumer_0 = "0" // just a mock as SubscriptionsState is unaware of consumers
  private val consumer_1 = "1"
  private val consumer_2 = "2"
  private val consumer_3 = "3"

  private lazy val subscription_addr_01_eventIndex_0 =
    SubscriptionOfConnection.fromParams(wsId_0, params_addr_01_eventIndex_0)

  private lazy val subscription_addr_12_eventIndex_1 =
    SubscriptionOfConnection.fromParams(wsId_0, params_addr_12_eventIndex_1)

  private lazy val subscription_addr_2_eventIndex_1 =
    SubscriptionOfConnection.fromParams(wsId_0, params_addr_2_eventIndex_1)

  it should "be idempotent on adding new contract event subscriptions" in {
    val subscriptionsState = WsSubscriptionsState.empty[String]()
    val contractEventKeys  = buildContractEventKeys(params_addr_01_eventIndex_0)
    subscriptionsState.addSubscriptionForContractEventKeys(
      contractEventKeys,
      subscription_addr_01_eventIndex_0
    )
    val result_1 = subscriptionsState.subscriptionsByContractKey.toMap
    subscriptionsState.addSubscriptionForContractEventKeys(
      contractEventKeys,
      subscription_addr_01_eventIndex_0
    )
    val result_2 = subscriptionsState.subscriptionsByContractKey.toMap
    result_1 is result_2
  }

  it should "be idempotent on adding new subscriptions" in {
    val subscriptionsState = WsSubscriptionsState.empty[String]()

    subscriptionsState.addNewSubscription(wsId_0, params_addr_01_eventIndex_0, consumer_0)
    val result_1 = subscriptionsState.subscriptionsByContractKey.toMap
    subscriptionsState.addNewSubscription(wsId_0, params_addr_01_eventIndex_0, consumer_0)
    val result_2 = subscriptionsState.subscriptionsByContractKey.toMap
    result_1 is result_2

    subscriptionsState.connections is mutable.Map(
      wsId_0 -> AVector(params_addr_01_eventIndex_0.subscriptionId -> consumer_0)
    )

    val contractEventKeys = buildContractEventKeys(params_addr_01_eventIndex_0)
    subscriptionsState.contractKeysBySubscription is mutable.Map(
      subscription_addr_01_eventIndex_0 -> contractEventKeys
    )

    subscriptionsState.subscriptionsByContractKey is mutable.Map.from(
      contractEventKeys.map(_ -> AVector(subscription_addr_01_eventIndex_0))
    )
  }

  it should "get consumers and subscriptions" in {
    val subscriptionsState = WsSubscriptionsState.empty[String]()

    subscriptionsState.addNewSubscription(wsId_0, params_addr_01_eventIndex_0, consumer_0)
    subscriptionsState.addNewSubscription(wsId_1, params_addr_12_eventIndex_1, consumer_1)
    subscriptionsState.addNewSubscription(wsId_1, params_addr_2_eventIndex_1, consumer_2)
    subscriptionsState.addNewSubscription(wsId_1, params_addr_3_unfiltered, consumer_3)

    subscriptionsState.getConsumer(wsId_0, params_addr_12_eventIndex_1.subscriptionId) is None
    subscriptionsState.getSubscriptions(wsId_0) is AVector(
      params_addr_01_eventIndex_0.subscriptionId
    )

    subscriptionsState.getConsumer(wsId_1, params_addr_12_eventIndex_1.subscriptionId) is Some(
      consumer_1
    )
    subscriptionsState.getConsumers(wsId_1) is AVector(consumer_1, consumer_2, consumer_3)
    subscriptionsState.getSubscriptions(wsId_1) is AVector(
      params_addr_12_eventIndex_1.subscriptionId,
      params_addr_2_eventIndex_1.subscriptionId,
      params_addr_3_unfiltered.subscriptionId
    )

    subscriptionsState.getConsumers("") is AVector.empty[String]
    subscriptionsState.getSubscriptions("") is AVector.empty[WsSubscriptionId]
  }

  "getting unique subscription ids" should "deduplicate subscription ids for given address" in {
    val subscriptionsState = WsSubscriptionsState.empty[String]()
    subscriptionsState.addNewSubscription(wsId_0, params_addr_01_eventIndex_0, consumer_0)
    subscriptionsState.addNewSubscription(wsId_1, params_addr_01_eventIndex_0, consumer_0)

    subscriptionsState.getUniqueSubscriptionIds(
      params_addr_01_eventIndex_0.addresses.head,
      params_addr_01_eventIndex_0.eventIndex.get
    ) is AVector(params_addr_01_eventIndex_0.subscriptionId)
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

    subscriptionsState.contractKeysBySubscription.size is 0
    subscriptionsState.subscriptionsByContractKey.size is 0
  }

  it should "add and remove subscriptions" in {
    val subscriptionsState = WsSubscriptionsState.empty[String]()

    subscriptionsState.addNewSubscription(wsId_0, params_addr_12_eventIndex_1, consumer_0)
    subscriptionsState.addNewSubscription(wsId_0, params_addr_2_eventIndex_1, consumer_0)

    subscriptionsState.connections is mutable.Map(
      wsId_0 -> AVector(
        params_addr_12_eventIndex_1.subscriptionId -> consumer_0,
        params_addr_2_eventIndex_1.subscriptionId  -> consumer_0
      )
    )

    subscriptionsState.contractKeysBySubscription is mutable.Map(
      subscription_addr_12_eventIndex_1 -> buildContractEventKeys(params_addr_12_eventIndex_1),
      subscription_addr_2_eventIndex_1  -> buildContractEventKeys(params_addr_2_eventIndex_1)
    )

    val contractEventKeys_1 = buildContractEventKeys(params_addr_12_eventIndex_1)
    val contractEventKeys_0 = buildContractEventKeys(params_addr_2_eventIndex_1)

    val expectedKeys = contractEventKeys_1 ++ contractEventKeys_0
    subscriptionsState.subscriptionsByContractKey.keys.toSet is expectedKeys.toSet

    val sharedSubscriptionsByContractEventKey =
      AVector(subscription_addr_12_eventIndex_1, subscription_addr_2_eventIndex_1)
    val sharedContractKeyBySubscriptions = contractEventKeys_0.head
    subscriptionsState.subscriptionsByContractKey(
      sharedContractKeyBySubscriptions
    ) is sharedSubscriptionsByContractEventKey

    subscriptionsState.removeSubscription(wsId_0, params_addr_12_eventIndex_1.subscriptionId)

    subscriptionsState.connections is mutable.Map(
      wsId_0 -> AVector(params_addr_2_eventIndex_1.subscriptionId -> consumer_0)
    )

    subscriptionsState.contractKeysBySubscription is mutable.Map(
      subscription_addr_2_eventIndex_1 -> contractEventKeys_0
    )

    subscriptionsState.subscriptionsByContractKey.keys.toSet is contractEventKeys_0.toSet
    subscriptionsState.subscriptionsByContractKey.foreachEntry { case (_, subscriptions) =>
      subscriptions.length is 1
      subscriptions.head is subscription_addr_2_eventIndex_1
    }
  }

  "removing subscription by contract event key" should "not allow for contract event keys with 0 subscriptions" in {
    val subscriptionsState = WsSubscriptionsState.empty[String]()
    subscriptionsState.addNewSubscription(wsId_0, params_addr_12_eventIndex_1, consumer_0)
    buildContractEventKeys(params_addr_12_eventIndex_1).map { contractKey =>
      subscriptionsState.removeSubscriptionByContractEventKey(
        contractKey,
        subscription_addr_12_eventIndex_1
      )
    }

    subscriptionsState.subscriptionsByContractKey.size is 0
  }

  it should "remove all subscriptions for given connection" in {
    val subscriptionsState = WsSubscriptionsState.empty[String]()

    subscriptionsState.addNewSubscription(wsId_0, params_addr_01_eventIndex_0, consumer_0)
    subscriptionsState.addNewSubscription(wsId_1, params_addr_12_eventIndex_1, consumer_1)
    subscriptionsState.addNewSubscription(wsId_1, params_addr_2_eventIndex_1, consumer_2)
    subscriptionsState.addNewSubscription(wsId_1, params_addr_3_unfiltered, consumer_3)

    subscriptionsState.removeAllSubscriptions(wsId_1)

    subscriptionsState.connections is mutable.Map(
      wsId_0 -> AVector(params_addr_01_eventIndex_0.subscriptionId -> consumer_0)
    )

    subscriptionsState.contractKeysBySubscription.forall(_._1.wsId == wsId_0) is true
    subscriptionsState.subscriptionsByContractKey.forall(_._2.forall(_.wsId == wsId_0)) is true
  }
}
