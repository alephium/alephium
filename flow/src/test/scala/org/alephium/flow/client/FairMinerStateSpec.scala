package org.alephium.flow.client

import scala.util.Random

import akka.actor.ActorRef
import akka.testkit.TestProbe
import org.scalacheck.Gen

import org.alephium.flow.AlephiumFlowActorSpec
import org.alephium.flow.core.{AllHandlers, BlockFlow, TestUtils}
import org.alephium.flow.model.BlockTemplate
import org.alephium.flow.platform.PlatformProfile
import org.alephium.protocol.model.ChainIndex
import org.alephium.util.AVector

class FairMinerStateSpec extends AlephiumFlowActorSpec("FairMinerState") { Spec =>
  val blockFlow: BlockFlow = BlockFlow.createUnsafe()

  trait Fixture extends FairMinerState {
    override implicit def config: PlatformProfile = Spec.config
    val handlers: AllHandlers                     = TestUtils.createBlockHandlersProbe
    val probes                                    = AVector.fill(config.groupNumPerBroker, config.groups)(TestProbe())

    override def prepareTemplate(fromShift: Int, to: Int): BlockTemplate = {
      val index        = ChainIndex(config.brokerInfo.groupFrom + fromShift, to)
      val flowTemplate = blockFlow.prepareBlockFlowUnsafe(index)
      BlockTemplate(flowTemplate.deps, flowTemplate.target, AVector.empty)
    }

    override def startTask(fromShift: Int,
                           to: Int,
                           template: BlockTemplate,
                           blockHandler: ActorRef): Unit = {
      probes(fromShift)(to).ref ! template
    }
  }

  it should "use correct collections" in new Fixture {
    miningCounts.length is config.groupNumPerBroker
    miningCounts.foreach(_.length is config.groups)
    running.length is config.groupNumPerBroker
    running.foreach(_.length is config.groups)
    pendingTasks.length is config.groupNumPerBroker
    pendingTasks.foreach(_.length is config.groups)
  }

  it should "start new tasks correctly" in new Fixture {
    startNewTasks()
    probes.foreach(_.foreach(_.expectMsgType[BlockTemplate]))
    running.foreach(_.foreach(_ is true))
  }

  it should "handle mining counts correctly" in new Fixture {
    forAll(Gen.choose(0, config.groupNumPerBroker - 1), Gen.choose(0, config.groups - 1)) {
      (fromShift, to) =>
        val oldCount   = getMiningCount(fromShift, to)
        val countDelta = Random.nextInt(Integer.MAX_VALUE)
        increaseCounts(fromShift, to, countDelta)
        val newCount = getMiningCount(fromShift, to)
        (newCount - oldCount) is countDelta
    }
  }

  it should "pick up correct task" in new Fixture {
    val fromShift = Random.nextInt(config.groupNumPerBroker)
    val to        = Random.nextInt(config.groups)
    (0 until config.groups).foreach { i =>
      if (i != to) increaseCounts(fromShift, i, config.nonceStep + 1)
    }
    startNewTasks()
    (0 until config.groups).foreach { i =>
      if (i != to) probes(fromShift)(i).expectNoMessage()
      else probes(fromShift)(i).expectMsgType[BlockTemplate]
    }
  }
}
