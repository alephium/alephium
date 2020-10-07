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

package org.alephium.util

import akka.testkit.TestProbe

class EventBusSpec extends AlephiumActorSpec("EventBus") {

  case object Dummy extends EventBus.Event

  trait Fixture { self =>
    val eventBus = system.actorOf(EventBus.props())
  }

  it should "forward event to one subscriber" in new Fixture {
    eventBus ! EventBus.Subscribe
    eventBus ! Dummy
    expectMsg(Dummy)
    eventBus ! EventBus.Unsubscribe
    eventBus ! Dummy
    expectNoMessage()
  }

  it should "broadcast event to many subscriber" in new Fixture {
    val probe0 = TestProbe()
    val probe1 = TestProbe()

    eventBus.tell(EventBus.Subscribe, probe0.ref)
    eventBus.tell(EventBus.Subscribe, probe1.ref)

    eventBus ! Dummy

    probe0.expectMsg(Dummy)
    probe1.expectMsg(Dummy)

    eventBus.tell(EventBus.Unsubscribe, probe0.ref)
    eventBus.tell(EventBus.Unsubscribe, probe1.ref)

    eventBus ! Dummy

    probe0.expectNoMessage()
    probe1.expectNoMessage()
  }
}
