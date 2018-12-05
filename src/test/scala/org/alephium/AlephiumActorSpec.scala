package org.alephium

import akka.actor.ActorSystem
import akka.testkit.TestKit
import org.scalatest.BeforeAndAfterAll

class AlephiumActorSpec(name: String)
    extends TestKit(ActorSystem(name))
    with AlephiumSpec
    with BeforeAndAfterAll {
  override def afterAll: Unit = {
    TestKit.shutdownActorSystem(system)
  }
}
