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

package org.alephium.flow.mining

import scala.util.Random

import akka.testkit.TestProbe
import org.scalacheck.Gen

import org.alephium.flow.AlephiumFlowActorSpec
import org.alephium.flow.handler.{AllHandlers, TestUtils, ViewHandler}
import org.alephium.flow.model.MiningBlob
import org.alephium.flow.setting.MiningSetting
import org.alephium.protocol.config.BrokerConfig
import org.alephium.protocol.model.ChainIndex
import org.alephium.util.AVector

class MinerStateSpec extends AlephiumFlowActorSpec { Spec =>

  val minerAddresses =
    AVector.tabulate(groups0)(g => getGenesisLockupScript(ChainIndex.unsafe(g, 0)))

  trait Fixture extends MinerState {
    implicit override def brokerConfig: BrokerConfig  = config.broker
    implicit override def miningConfig: MiningSetting = config.mining

    val allHandlers: AllHandlers = TestUtils.createAllHandlersProbe._1
    val probes = AVector.fill(brokerConfig.groupNumPerBroker, brokerConfig.groups)(TestProbe())

    def updateAndStartTasks(): Unit = {
      val templates = ViewHandler.prepareTemplates(blockFlow, minerAddresses).rightValue
      for {
        fromShift <- 0 until brokerConfig.groupNumPerBroker
        to        <- 0 until brokerConfig.groups
      } {
        val miningBlob = MiningBlob.from(templates(fromShift)(to))
        pendingTasks(fromShift)(to) = miningBlob
      }
      startNewTasks()
    }

    override def startTask(
        fromShift: Int,
        to: Int,
        template: MiningBlob
    ): Unit = {
      probes(fromShift)(to).ref ! template
    }
  }

  it should "use correct collections" in new Fixture {
    miningCounts.length is brokerConfig.groupNumPerBroker
    miningCounts.foreach(_.length is brokerConfig.groups)
    running.length is brokerConfig.groupNumPerBroker
    running.foreach(_.length is brokerConfig.groups)
    pendingTasks.length is brokerConfig.groupNumPerBroker
    pendingTasks.foreach(_.length is brokerConfig.groups)
  }

  it should "start new tasks correctly" in new Fixture {
    updateAndStartTasks()
    startNewTasks()
    probes.foreach(_.foreach(_.expectMsgType[MiningBlob]))
    running.foreach(_.foreach(_ is true))
  }

  it should "be idle after stopping" in new Fixture {
    postMinerStop()
    running.foreach(_.foreach(_ is false))
  }

  it should "handle mining counts correctly" in new Fixture {
    forAll(
      Gen.choose(0, brokerConfig.groupNumPerBroker - 1),
      Gen.choose(0, brokerConfig.groups - 1)
    ) { (fromShift, to) =>
      val oldCount   = getMiningCount(fromShift, to)
      val countDelta = Random.nextInt(Integer.MAX_VALUE)
      increaseCounts(fromShift, to, countDelta)
      val newCount = getMiningCount(fromShift, to)
      (newCount - oldCount) is countDelta
    }
  }

  it should "pick up correct task" in new Fixture {
    val fromShift = Random.nextInt(brokerConfig.groupNumPerBroker)
    val to        = Random.nextInt(brokerConfig.groups)
    (0 until brokerConfig.groups).foreach { i =>
      if (i != to) increaseCounts(fromShift, i, miningConfig.nonceStep + 1)
    }
    updateAndStartTasks()
    (0 until brokerConfig.groups).foreach { i =>
      if (i != to) {
        probes(fromShift)(i).expectNoMessage()
      } else {
        probes(fromShift)(i).expectMsgType[MiningBlob]
      }
    }
  }
}
