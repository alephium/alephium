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

import org.alephium.flow.setting.MiningSetting
import org.alephium.protocol.config.BrokerConfig
import org.alephium.protocol.model.ChainIndex
import org.alephium.util.U256

trait MinerState {
  implicit def brokerConfig: BrokerConfig
  implicit def miningConfig: MiningSetting

  protected val miningCounts =
    Array.fill[U256](brokerConfig.groupNumPerBroker, brokerConfig.groups)(U256.Zero)
  protected val running = Array.fill(brokerConfig.groupNumPerBroker, brokerConfig.groups)(false)
  protected val pendingTasks =
    Array.fill(brokerConfig.groupNumPerBroker)(Array.fill[Option[Job]](brokerConfig.groups)(None))

  def getMiningCount(fromShift: Int, to: Int): U256 = miningCounts(fromShift)(to)

  def isRunning(fromShift: Int, to: Int): Boolean = running(fromShift)(to)

  def setRunning(fromShift: Int, to: Int): Unit = running(fromShift)(to) = true

  def setIdle(chainIndex: ChainIndex): Unit = {
    val fromShift = brokerConfig.groupIndexOfBroker(chainIndex.from)
    val to        = chainIndex.to.value
    setIdle(fromShift, to)
  }

  def setIdle(fromShift: Int, to: Int): Unit = running(fromShift)(to) = false

  def countsToString: String = {
    miningCounts.map(_.mkString(",")).mkString(",")
  }

  def increaseCounts(fromShift: Int, to: Int, count: U256): Unit = {
    miningCounts(fromShift)(to) = miningCounts(fromShift)(to).addUnsafe(count)
  }

  @SuppressWarnings(
    Array("org.wartremover.warts.IterableOps", "org.wartremover.warts.OptionPartial")
  )
  protected def pickTasks(): IndexedSeq[(Int, Int, Job)] = {
    // When a block is mined, we remove the corresponding mining job from `pendingTasks` to avoid mining the same job again.
    // However, if the block is invalid (e.g., it's a duplicate), the `MinerApiController` won't send a new mining job.
    // If the chain index of this block has the lowest mining count, then due to the `countBound` constraint, `pickTasks` might
    // return an empty array. Therefore, when calculating `minCount`, we need to exclude chain indexes whose `pendingTasks` are empty.
    val filteredCounts = for {
      fromShift <- 0 until brokerConfig.groupNumPerBroker
      to        <- 0 until brokerConfig.groups
      if pendingTasks(fromShift)(to).nonEmpty
    } yield miningCounts(fromShift)(to)
    val minCount   = filteredCounts.minOption.getOrElse(miningCounts.map(_.min).min)
    val countBound = minCount.addUnsafe(miningConfig.nonceStep)
    for {
      fromShift <- 0 until brokerConfig.groupNumPerBroker
      to        <- 0 until brokerConfig.groups
      if miningCounts(fromShift)(to) <= countBound &&
        !isRunning(fromShift, to) &&
        pendingTasks(fromShift)(to).nonEmpty
    } yield {
      (fromShift, to, pendingTasks(fromShift)(to).get)
    }
  }

  @volatile var tasksReady: Boolean = false
  protected def startNewTasks(): Unit = {
    if (!tasksReady) {
      tasksReady = pendingTasks.forall(_.forall(_.nonEmpty))
    }
    if (tasksReady) {
      pickTasks().foreach { case (fromShift, to, job) =>
        startTask(fromShift, to, job)
        setRunning(fromShift, to)
      }
    }
  }

  protected def postMinerStop(): Unit = {
    for {
      fromShift <- 0 until brokerConfig.groupNumPerBroker
      to        <- 0 until brokerConfig.groups
    } setIdle(fromShift, to)
  }

  def startTask(
      fromShift: Int,
      to: Int,
      job: Job
  ): Unit
}
