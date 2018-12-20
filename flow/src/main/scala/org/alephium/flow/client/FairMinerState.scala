package org.alephium.flow.client

import org.alephium.flow.PlatformConfig
import org.alephium.flow.storage.BlockFlow
import org.alephium.flow.storage.FlowHandler.BlockFlowTemplate
import org.alephium.protocol.model.ChainIndex

import scala.concurrent.duration._

trait FairMinerState {
  implicit def config: PlatformConfig

  def blockFlow: BlockFlow

  protected val miningCounts        = Array.fill[BigInt](config.groups)(0)
  protected val taskRefreshDuration = 10.seconds.toMillis
  protected val taskRefreshTss      = Array.fill[Long](config.groups)(-1)
  protected val pendingTasks        = collection.mutable.Map.empty[Int, BlockFlowTemplate]

  def initializeState(): Unit = {
    (0 until config.groups).foreach(refresh)
  }

  def getMiningCount(to: Int): BigInt = miningCounts(to)

  def increaseCounts(to: Int, count: BigInt): Unit = {
    miningCounts(to) += count
  }

  def refresh(to: Int): Unit = {
    assert(to >= 0 && to < config.groups)
    val index    = ChainIndex(config.mainGroup.value, to)
    val template = blockFlow.prepareBlockFlowUnsafe(index)
    taskRefreshTss(to) = System.currentTimeMillis()
    pendingTasks(to)   = template
  }

  def tryToRefresh(to: Int): Unit = {
    val lastRefreshTs = taskRefreshTss(to)
    val currentTs     = System.currentTimeMillis()
    if (currentTs - lastRefreshTs > taskRefreshDuration) {
      refresh(to)
    }
  }

  def removeTask(to: Int): Unit = {
    pendingTasks -= to
  }

  def pickNextTemplate(): (Int, BlockFlowTemplate) = {
    val toTry    = pendingTasks.keys.minBy(miningCounts)
    val template = pendingTasks(toTry)
    (toTry, template)
  }
}
