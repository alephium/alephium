package org.alephium.flow.client

import akka.actor.ActorRef
import akka.testkit.TestProbe
import org.alephium.flow.PlatformConfig
import org.alephium.flow.model.BlockTemplate
import org.alephium.flow.storage.{AllHandlers, BlockFlow, BlockFlowFixture, TestUtils}
import org.alephium.protocol.model.ChainIndex
import org.alephium.util.{AVector, AlephiumActorSpec}
import org.scalacheck.Gen

import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.Random

class FairMinerStateSpec extends AlephiumActorSpec("FairMinerState") with BlockFlowFixture { Spec =>

  trait Fixture extends FairMinerState {
    override implicit def config: PlatformConfig = Spec.config
    val blockFlow: BlockFlow                     = BlockFlow.createUnsafe()
    val handlers: AllHandlers                    = TestUtils.createBlockHandlersProbe
    val probes                                   = AVector.fill(config.groupNumPerBroker, config.groups)(TestProbe())

    def prepareBlockTemplate(fromShift: Int, to: Int): BlockTemplate = {
      val index        = ChainIndex(config.groupFrom + fromShift, to)
      val flowTemplate = blockFlow.prepareBlockFlowUnsafe(index)
      BlockTemplate(flowTemplate.deps, flowTemplate.target, AVector.empty)
    }

    override def prepareTemplate(fromShift: Int, to: Int): Unit = {
      val blockTemplate = prepareBlockTemplate(fromShift, to)
      addNewTask(fromShift, to, blockTemplate)
    }

    override def startTask(fromShift: Int,
                           to: Int,
                           template: BlockTemplate,
                           blockHandler: ActorRef): Future[Unit] = Future {
      probes(fromShift)(to).ref ! template
    }

    miningCounts.length is config.groupNumPerBroker
    miningCounts.foreach(_.length is config.groups)
    taskRefreshTss.length is config.groupNumPerBroker
    taskRefreshTss.foreach(_.length is config.groups)
    pendingTasks.isEmpty is true

    initialize()
  }

  it should "initialize correctly" in new Fixture {
    pendingTasks.size is 0
    probes.foreach(_.foreach(_.expectMsgType[BlockTemplate]))
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

  it should "refresh and add new task correctly" in new Fixture {
    override def startNewTasks(): Unit = ()

    forAll(Gen.choose(0, config.groupNumPerBroker - 1), Gen.choose(0, config.groups - 1)) {
      (fromShift, to) =>
        val key = (fromShift, to)
        pendingTasks.contains(key) is true
        pendingTasks -= key
        pendingTasks.contains(key) is false
        prepareTemplate(fromShift, to)
        pendingTasks.contains(key) is true
    }
  }

  it should "refresh last task correctly" in new Fixture {
    probes.foreach(_.foreach(_.expectMsgType[BlockTemplate]))
    forAll(Gen.choose(0, config.groupNumPerBroker - 1), Gen.choose(0, config.groups - 1)) {
      (fromShift, to) =>
        val template = prepareBlockTemplate(fromShift, to)
        refreshLastTask(fromShift, to, template)
        probes(fromShift)(to).expectMsgType[BlockTemplate]
    }
  }

  it should "pick up correct task" in new Fixture {
    probes.foreach(_.foreach(_.expectMsgType[BlockTemplate]))
    val fromShift = Random.nextInt(config.groupNumPerBroker)
    val to        = Random.nextInt(config.groups)
    (0 until config.groups).foreach { i =>
      if (i != to) increaseCounts(fromShift, i, config.nonceStep + 1)
      else prepareTemplate(fromShift, i)
    }
    (0 until config.groups).foreach { i =>
      if (i != to) probes(fromShift)(i).expectNoMessage()
      else probes(fromShift)(i).expectMsgType[BlockTemplate]
    }
  }
}
