package org.alephium.flow.core

import scala.collection.mutable

import akka.testkit.TestProbe

import org.alephium.flow.AlephiumFlowActorSpec
import org.alephium.flow.model.DataOrigin
import org.alephium.protocol.ALF.Hash
import org.alephium.protocol.model.{Block, NoIndexModelGeneratorsLike}
import org.alephium.util.ActorRefT

class FlowHandlerSpec extends AlephiumFlowActorSpec("FlowHandler") with NoIndexModelGeneratorsLike {
  import FlowHandler._

  def genPending(block: Block): PendingBlock = {
    genPending(block, mutable.HashSet.empty)
  }

  def genPending(missings: mutable.HashSet[Hash]): PendingBlock = {
    val block = blockGen.sample.get
    genPending(block, missings)
  }

  def genPending(block: Block, missings: mutable.HashSet[Hash]): PendingBlock = {
    PendingBlock(block,
                 missings,
                 DataOrigin.Local,
                 ActorRefT(TestProbe().ref),
                 ActorRefT(TestProbe().ref))
  }

  trait StateFix {
    val block = blockGen.sample.get
  }

  it should "add status" in {
    val state = new FlowHandlerState { override def statusSizeLimit: Int = 2 }
    state.pendingStatus.size is 0

    val pending0 = genPending(mutable.HashSet.empty[Hash])
    state.addStatus(pending0)
    state.pendingStatus.size is 1
    state.pendingStatus.head._2 is pending0
    state.pendingStatus.last._2 is pending0
    state.counter is 1

    val pending1 = genPending(mutable.HashSet.empty[Hash])
    state.addStatus(pending1)
    state.pendingStatus.size is 2
    state.pendingStatus.head._2 is pending0
    state.pendingStatus.last._2 is pending1
    state.counter is 2

    val pending2 = genPending(mutable.HashSet.empty[Hash])
    state.addStatus(pending2)
    state.pendingStatus.size is 2
    state.pendingStatus.head._2 is pending1
    state.pendingStatus.last._2 is pending2
    state.counter is 3
  }

  it should "update status" in {
    val state  = new FlowHandlerState { override def statusSizeLimit: Int = 3 }
    val block0 = blockGen.sample.get
    val block1 = blockGen.sample.get
    val block2 = blockGen.sample.get

    val pending0 = genPending(block0, mutable.HashSet(block1.hash, block2.hash))
    state.addStatus(pending0)
    state.pendingStatus.size is 1
    state.pendingStatus.head._2.missingDeps.size is 2
    state.counter is 1

    val readies1 = state.updateStatus(block1.hash)
    readies1.size is 0
    state.pendingStatus.size is 1
    state.pendingStatus.head._2.missingDeps.size is 1
    state.counter is 1

    val readies2 = state.updateStatus(block2.hash).toList
    readies2.size is 1
    readies2.head is pending0
    state.pendingStatus.size is 0
    state.counter is 1
  }
}
