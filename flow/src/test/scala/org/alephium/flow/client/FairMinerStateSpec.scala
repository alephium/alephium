package org.alephium.flow.client

import akka.actor.ActorRef
import akka.testkit.TestProbe
import org.alephium.flow.PlatformConfig
import org.alephium.flow.model.BlockTemplate
import org.alephium.flow.storage.{BlockFlow, BlockFlowFixture}
import org.alephium.protocol.model.ChainIndex
import org.alephium.util.{AVector, AlephiumActorSpec}
import org.scalacheck.Gen

import scala.util.Random

class FairMinerStateSpec extends AlephiumActorSpec("FairMinerState") with BlockFlowFixture { Spec =>

  trait Fixture extends FairMinerState {
    override implicit def config: PlatformConfig = Spec.config
    val blockFlow: BlockFlow                     = BlockFlow.createUnsafe()
    val probes                                   = AVector.fill(config.groups)(TestProbe())
    override val actualMiners: AVector[ActorRef] =
      AVector.tabulate(config.groups)(i => probes(i).ref)

    def prepareBlockTemplate(to: Int): BlockTemplate = {
      val index        = ChainIndex(config.mainGroup.value, to)
      val flowTemplate = blockFlow.prepareBlockFlowUnsafe(index)
      BlockTemplate(flowTemplate.deps, flowTemplate.target, AVector.empty)
    }

    override def prepareTemplate(to: Int): Unit = {
      val blockTemplate = prepareBlockTemplate(to)
      addNewTask(to, blockTemplate)
    }

    override def startTask(to: Int, template: BlockTemplate): Unit = {
      actualMiners(to) ! ActualMiner.Task(template)
    }

    miningCounts.length is config.groups
    taskRefreshTss.length is config.groups
    pendingTasks.isEmpty is true

    initialize()
  }

  it should "initialize correctly" in new Fixture {
    pendingTasks.size is 0
    probes.foreach(_.expectMsgType[ActualMiner.Task])
  }

  it should "handle mining counts correctly" in new Fixture {
    forAll(Gen.choose(0, config.groups - 1)) { to =>
      val oldCount   = getMiningCount(to)
      val countDelta = Random.nextInt(Integer.MAX_VALUE)
      increaseCounts(to, countDelta)
      val newCount = getMiningCount(to)
      (newCount - oldCount) is countDelta
    }
  }

  it should "refresh and add new task correctly" in new Fixture {
    override def startNewTasks(): Unit = ()

    forAll(Gen.choose(0, config.groups - 1)) { to =>
      pendingTasks.contains(to) is true
      pendingTasks -= to
      pendingTasks.contains(to) is false
      prepareTemplate(to)
      pendingTasks.contains(to) is true
    }
  }

  it should "refresh last task correctly" in new Fixture {
    probes.foreach(_.expectMsgType[ActualMiner.Task])
    forAll(Gen.choose(0, config.groups - 1)) { to =>
      val template = prepareBlockTemplate(to)
      refreshLastTask(to, template)
      probes(to).expectMsgType[ActualMiner.Task]
    }
  }

  it should "pick up correct task" in new Fixture {
    probes.foreach(_.expectMsgType[ActualMiner.Task])
    val to = Random.nextInt(config.groups)
    (0 until config.groups).foreach { i =>
      if (i != to) increaseCounts(i, config.nonceStep + 1) else prepareTemplate(i)
    }
    (0 until config.groups).foreach { i =>
      if (i != to) probes(i).expectNoMessage() else probes(i).expectMsgType[ActualMiner.Task]
    }
  }
}
