package org.alephium.flow.client

import org.alephium.flow.PlatformConfig
import org.alephium.flow.storage.{BlockFlow, BlockFlowFixture}
import org.alephium.util.AlephiumSpec
import org.scalacheck.Gen

import scala.language.reflectiveCalls
import scala.util.Random

class FairMinerStateSpec extends AlephiumSpec with BlockFlowFixture { Spec =>
  trait Fixture {
    val state = new FairMinerState {
      override implicit def config: PlatformConfig = Spec.config
      override val blockFlow: BlockFlow            = BlockFlow.createUnsafe()

      def getPendingTasks = pendingTasks

      miningCounts.length is config.groups
      taskRefreshTss.length is config.groups
      pendingTasks.isEmpty is true
    }
  }

  it should "initialize correctly" in new Fixture {
    state.initializeState()
    state.getPendingTasks.size is config.groups
  }

  it should "handle mining counts correctly" in new Fixture {
    forAll(Gen.choose(0, config.groups - 1)) { to =>
      val oldCount   = state.getMiningCount(to)
      val countDelta = Random.nextInt(Integer.MAX_VALUE)
      state.increaseCounts(to, countDelta)
      val newCount = state.getMiningCount(to)
      (newCount - oldCount) is countDelta
    }
  }

  it should "handle tasks correctly" in new Fixture {
    state.initializeState()
    forAll(Gen.choose(0, config.groups - 1)) { to =>
      state.getPendingTasks.contains(to) is true
      state.removeTask(to)
      state.getPendingTasks.contains(to) is false
      state.refresh(to)
      state.getPendingTasks.contains(to) is true
    }
  }

  it should "pick up correct task" in new Fixture {
    state.initializeState()
    val to = Random.nextInt(config.groups)
    (0 until config.groups).foreach { i =>
      if (i != to) state.increaseCounts(i, 1)
    }
    val (toPicked, _) = state.pickNextTemplate()
    toPicked is to
  }
}
