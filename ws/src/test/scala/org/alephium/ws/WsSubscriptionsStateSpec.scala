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

package org.alephium.ws

import org.scalatest.concurrent.ScalaFutures
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{Seconds, Span}

import org.alephium.protocol.model.{Address, ContractId}
import org.alephium.protocol.vm.LockupScript
import org.alephium.util.AVector
import org.alephium.ws.WsParams.ContractEventsSubscribeParams
import org.alephium.ws.WsSubscriptionsState.{AddressKey, AddressWithEventIndexKey}

class WsSubscriptionsStateSpec extends AnyFlatSpec with Matchers with ScalaFutures {
  implicit override val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = Span(15, Seconds))

  case class TestConsumer(id: Int)

  def generateContractAddress(): Address.Contract = {
    Address.Contract(LockupScript.p2c(ContractId.generate))
  }

  it should "add and retrieve subscriptions" in {
    val state    = WsSubscriptionsState.empty[TestConsumer]()
    val wsId     = "test-ws-1"
    val consumer = TestConsumer(1)
    val address  = generateContractAddress()
    val params   = ContractEventsSubscribeParams(AVector(address), None)

    state.addNewSubscription(wsId, params, consumer)

    state.getConsumer(wsId, params.subscriptionId).isDefined should be(true)
    state.getConsumers(wsId).length should be(1)
    state.getSubscriptions(wsId).length should be(1)
  }

  it should "get unique subscription IDs for contract events" in {
    val state      = WsSubscriptionsState.empty[TestConsumer]()
    val wsId1      = "test-ws-1"
    val wsId2      = "test-ws-2"
    val consumer1  = TestConsumer(1)
    val consumer2  = TestConsumer(2)
    val address    = generateContractAddress()
    val eventIndex = 1

    val params1 = ContractEventsSubscribeParams(AVector(address), Some(eventIndex))
    val params2 = ContractEventsSubscribeParams(AVector(address), None)

    state.addNewSubscription(wsId1, params1, consumer1)
    state.addNewSubscription(wsId2, params2, consumer2)

    val uniqueIds = state.getUniqueSubscriptionIds(address, eventIndex)
    uniqueIds.contains(params1.subscriptionId) should be(true)
    uniqueIds.contains(params2.subscriptionId) should be(true)
  }

  it should "remove specific subscription" in {
    val state     = WsSubscriptionsState.empty[TestConsumer]()
    val wsId      = "test-ws-1"
    val consumer1 = TestConsumer(1)
    val consumer2 = TestConsumer(2)
    val address1  = generateContractAddress()
    val address2  = generateContractAddress()

    val params1 = ContractEventsSubscribeParams(AVector(address1), None)
    val params2 = ContractEventsSubscribeParams(AVector(address2), None)

    state.addNewSubscription(wsId, params1, consumer1)
    state.addNewSubscription(wsId, params2, consumer2)

    state.getSubscriptions(wsId).length should be(2)

    state.removeSubscription(wsId, params1.subscriptionId)

    state.getSubscriptions(wsId).length should be(1)
    state.getConsumer(wsId, params1.subscriptionId).isEmpty should be(true)
    state.getConsumer(wsId, params2.subscriptionId).isDefined should be(true)
  }

  it should "remove all subscriptions for a connection" in {
    val state     = WsSubscriptionsState.empty[TestConsumer]()
    val wsId      = "test-ws-1"
    val consumer1 = TestConsumer(1)
    val consumer2 = TestConsumer(2)
    val address1  = generateContractAddress()
    val address2  = generateContractAddress()

    val params1 = ContractEventsSubscribeParams(AVector(address1), None)
    val params2 = ContractEventsSubscribeParams(AVector(address2), None)

    state.addNewSubscription(wsId, params1, consumer1)
    state.addNewSubscription(wsId, params2, consumer2)

    state.getSubscriptions(wsId).length should be(2)

    state.removeAllSubscriptions(wsId)

    state.getSubscriptions(wsId).isEmpty should be(true)
    state.getConsumers(wsId).isEmpty should be(true)
  }

  it should "build contract event keys correctly" in {
    val address1   = generateContractAddress()
    val address2   = generateContractAddress()
    val eventIndex = 42

    val paramsWithIndex = ContractEventsSubscribeParams(
      AVector(address1, address2),
      Some(eventIndex)
    )

    val keysWithIndex = WsSubscriptionsState.buildContractEventKeys(paramsWithIndex)
    keysWithIndex.length should be(2)
    keysWithIndex.contains(AddressWithEventIndexKey(address1.toBase58, eventIndex)) should be(true)
    keysWithIndex.contains(AddressWithEventIndexKey(address2.toBase58, eventIndex)) should be(true)

    val paramsWithoutIndex = ContractEventsSubscribeParams(
      AVector(address1, address2),
      None
    )

    val keysWithoutIndex = WsSubscriptionsState.buildContractEventKeys(paramsWithoutIndex)
    keysWithoutIndex.length should be(2)
    keysWithoutIndex.contains(AddressKey(address1.toBase58)) should be(true)
    keysWithoutIndex.contains(AddressKey(address2.toBase58)) should be(true)
  }

  it should "handle multiple addresses in subscription" in {
    val state      = WsSubscriptionsState.empty[TestConsumer]()
    val wsId       = "test-ws-1"
    val consumer   = TestConsumer(1)
    val address1   = generateContractAddress()
    val address2   = generateContractAddress()
    val eventIndex = 1

    val params = ContractEventsSubscribeParams(
      AVector(address1, address2),
      Some(eventIndex)
    )

    state.addNewSubscription(wsId, params, consumer)

    state.getUniqueSubscriptionIds(address1, eventIndex).contains(params.subscriptionId) should be(
      true
    )
    state.getUniqueSubscriptionIds(address2, eventIndex).contains(params.subscriptionId) should be(
      true
    )
  }

  it should "return empty when no subscriptions exist" in {
    val state      = WsSubscriptionsState.empty[TestConsumer]()
    val wsId       = "test-ws-1"
    val address    = generateContractAddress()
    val eventIndex = 1

    state.getConsumers(wsId).isEmpty should be(true)
    state.getSubscriptions(wsId).isEmpty should be(true)
    state.getUniqueSubscriptionIds(address, eventIndex).isEmpty should be(true)
  }
}
