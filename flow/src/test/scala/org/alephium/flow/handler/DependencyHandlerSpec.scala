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

package org.alephium.flow.handler

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer

import akka.actor.Props
import akka.testkit.{TestActorRef, TestProbe}

import org.alephium.flow.FlowFixture
import org.alephium.flow.core.BlockFlow
import org.alephium.flow.model.DataOrigin
import org.alephium.protocol.model.{ChainIndex, FlowData}
import org.alephium.util.{ActorRefT, AlephiumActorSpec}

class DependencyHandlerSpec extends AlephiumActorSpec("DependencyHandlerSpec") {
  trait Fixture extends FlowFixture { Self =>
    override val configValues = Map(("alephium.broker.broker-num", 1))

    val brokerProbe = TestProbe()
    val broker      = ActorRefT[ChainHandler.Event](brokerProbe.ref)
    val origin      = DataOrigin.Local

    val stateActor = TestActorRef[DependencyHandlerState](
      Props(
        new DependencyHandlerState {
          override def blockFlow: BlockFlow = Self.blockFlow
          override def receive: Receive     = _ => ()
        }
      ))
    val state = stateActor.underlyingActor
  }

  it should "work for valid data" in new Fixture {
    val blockFlow1 = isolatedBlockFlow()

    val block0 = mineFromMemPool(blockFlow1, ChainIndex.unsafe(0, 0))
    state.addPendingData(block0, broker, origin)
    state.pending(block0.hash) is ((block0, broker, origin))
    state.missing.isEmpty is true
    state.missingIndex.isEmpty is true
    state.readies is mutable.HashSet(block0.hash)
    state.processing.isEmpty is true

    addAndCheck(blockFlow1, block0)
    val block1 = mineFromMemPool(blockFlow1, ChainIndex.unsafe(0, 0))
    val block2 = mineFromMemPool(blockFlow1, ChainIndex.unsafe(1, 1))
    state.addPendingData(block1, broker, origin)
    state.addPendingData(block2, broker, origin)
    state.pending(block1.hash) is ((block1, broker, origin))
    state.pending(block2.hash) is ((block2, broker, origin))
    state.missing.keys.toSet is Set(block1.hash, block2.hash)
    state.missing(block1.hash) is ArrayBuffer(block0.hash)
    state.missing(block2.hash) is ArrayBuffer(block0.hash)
    state.missingIndex(block0.hash) is ArrayBuffer(block1.hash, block2.hash)
    state.readies is mutable.HashSet(block0.hash)
    state.processing.isEmpty is true

    addAndCheck(blockFlow1, block1)
    addAndCheck(blockFlow1, block2)
    val block3 = mineFromMemPool(blockFlow1, ChainIndex.unsafe(0, 0))
    state.addPendingData(block3, broker, origin)
    state.pending(block3.hash) is ((block3, broker, origin))
    state.missing.keys.toSet is Set(block1.hash, block2.hash, block3.hash)
    state.missing(block3.hash).toSet is Set(block1.hash, block2.hash)
    state.missingIndex(block0.hash) is ArrayBuffer(block1.hash, block2.hash)
    state.missingIndex(block1.hash) is ArrayBuffer(block3.hash)
    state.readies is mutable.HashSet(block0.hash)
    state.processing.isEmpty is true

    state.extractReadies().toSeq is Seq((block0, broker, origin))
    state.readies.isEmpty is true
    state.processing is mutable.HashSet(block0.hash)

    state.uponDataProcessed(block0)
    state.pending.contains(block0.hash) is false
    state.pending.contains(block1.hash) is true
    state.pending.contains(block2.hash) is true
    state.pending.contains(block3.hash) is true
    state.missing.keys.toSet is Set(block3.hash)
    state.missing(block3.hash).toSet is Set(block1.hash, block2.hash)
    state.missingIndex.keys.toSet is Set(block1.hash, block2.hash)
    state.missingIndex(block1.hash) is ArrayBuffer(block3.hash)
    state.missingIndex(block2.hash) is ArrayBuffer(block3.hash)
    state.readies is mutable.HashSet(block1.hash, block2.hash)
    state.processing.isEmpty is true

    state.extractReadies().toSet is
      Set[(FlowData, ActorRefT[ChainHandler.Event], DataOrigin)]((block1, broker, origin),
                                                                 (block2, broker, origin))
    state.readies.isEmpty is true
    state.processing is mutable.HashSet(block1.hash, block2.hash)

    state.uponDataProcessed(block1)
    state.pending.contains(block0.hash) is false
    state.pending.contains(block1.hash) is false
    state.pending.contains(block2.hash) is true
    state.pending.contains(block3.hash) is true
    state.missing.keys.toSet is Set(block3.hash)
    state.missing(block3.hash).toSet is Set(block2.hash)
    state.missingIndex.keys.toSet is Set(block2.hash)
    state.missingIndex(block2.hash) is ArrayBuffer(block3.hash)
    state.readies.isEmpty is true
    state.processing is mutable.HashSet(block2.hash)

    state.uponDataProcessed(block2)
    state.pending.contains(block0.hash) is false
    state.pending.contains(block1.hash) is false
    state.pending.contains(block2.hash) is false
    state.pending.contains(block3.hash) is true
    state.missing.isEmpty is true
    state.missingIndex.isEmpty is true
    state.readies is mutable.HashSet(block3.hash)
    state.processing.isEmpty is true

    state.extractReadies().toSeq is Seq((block3, broker, origin))
    state.readies.isEmpty is true
    state.processing is mutable.HashSet(block3.hash)

    state.uponDataProcessed(block3)
    state.pending.isEmpty is true
    state.missingIndex.isEmpty is true
    state.readies.isEmpty is true
    state.processing.isEmpty is true
  }

  it should "work for invalid data" in new Fixture {
    val blockFlow1 = isolatedBlockFlow()
    val block0     = mineFromMemPool(blockFlow1, ChainIndex.unsafe(0, 0))
    addAndCheck(blockFlow1, block0)
    val block1 = mineFromMemPool(blockFlow1, ChainIndex.unsafe(0, 0))

    state.addPendingData(block0, broker, origin)
    state.addPendingData(block1, broker, origin)
    state.readies is mutable.HashSet(block0.hash)

    state.uponInvalidData(block0.hash)
    state.pending.isEmpty is true
    state.missing.isEmpty is true
    state.missingIndex.isEmpty is true
    state.readies.isEmpty is true
    state.processing.isEmpty is true
  }

  it should "work for unordered datas" in new Fixture {
    val blockFlow1 = isolatedBlockFlow()
    val block0     = mineFromMemPool(blockFlow1, ChainIndex.unsafe(0, 0))
    addAndCheck(blockFlow1, block0)
    val block1 = mineFromMemPool(blockFlow1, ChainIndex.unsafe(0, 0))

    state.addPendingData(block1, broker, origin)
    state.addPendingData(block0, broker, origin)
    state.pending(block0.hash) is ((block0, broker, origin))
    state.pending(block1.hash) is ((block1, broker, origin))
    state.missing.keys.toSet is Set(block1.hash)
    state.missing(block1.hash) is ArrayBuffer(block0.hash)
    state.missingIndex.keys.toSet is Set(block0.hash)
    state.missingIndex(block0.hash) is ArrayBuffer(block1.hash)
    state.readies is mutable.HashSet(block0.hash)
    state.processing.isEmpty is true
  }

  it should "not pend existing blocks" in new Fixture {
    val block = mineFromMemPool(blockFlow, ChainIndex.unsafe(0, 0))
    addAndCheck(blockFlow, block)
    state.addPendingData(block, broker, origin)
    state.pending.isEmpty is true
    state.missing.isEmpty is true
    state.missingIndex.isEmpty is true
    state.readies.isEmpty is true
    state.processing.isEmpty is true
  }
}
