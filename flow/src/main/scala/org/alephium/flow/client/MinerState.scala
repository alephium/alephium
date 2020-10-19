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

package org.alephium.flow.client

import org.alephium.flow.handler.{AllHandlers, BlockChainHandler}
import org.alephium.flow.model.BlockTemplate
import org.alephium.flow.setting.MiningSetting
import org.alephium.protocol.config.BrokerConfig
import org.alephium.protocol.model.ChainIndex
import org.alephium.util.ActorRefT

trait MinerState {
  implicit def brokerConfig: BrokerConfig
  implicit def miningConfig: MiningSetting

  def handlers: AllHandlers

  protected val miningCounts =
    Array.fill[BigInt](brokerConfig.groupNumPerBroker, brokerConfig.groups)(0)
  protected val running = Array.fill(brokerConfig.groupNumPerBroker, brokerConfig.groups)(false)
  protected lazy val pendingTasks =
    Array.tabulate(brokerConfig.groupNumPerBroker, brokerConfig.groups)(prepareTemplate)

  def getMiningCount(fromShift: Int, to: Int): BigInt = miningCounts(fromShift)(to)

  def isRunning(fromShift: Int, to: Int): Boolean = running(fromShift)(to)

  def setRunning(fromShift: Int, to: Int): Unit = running(fromShift)(to) = true

  def setIdle(fromShift: Int, to: Int): Unit = running(fromShift)(to) = false

  def countsToString: String = {
    miningCounts.map(_.mkString(",")).mkString(",")
  }

  def increaseCounts(fromShift: Int, to: Int, count: BigInt): Unit = {
    miningCounts(fromShift)(to) += count
  }

  def updateTasks(): Unit = {
    for {
      fromShift <- 0 until brokerConfig.groupNumPerBroker
      to        <- 0 until brokerConfig.groups
    } {
      val blockTemplate = prepareTemplate(fromShift, to)
      pendingTasks(fromShift)(to) = blockTemplate
    }
  }

  @SuppressWarnings(Array("org.wartremover.warts.TraversableOps"))
  protected def pickTasks(): IndexedSeq[(Int, Int, BlockTemplate)] = {
    val minCount = miningCounts.map(_.min).min
    for {
      fromShift <- 0 until brokerConfig.groupNumPerBroker
      to        <- 0 until brokerConfig.groups
      if miningCounts(fromShift)(to) <= minCount + miningConfig.nonceStep &&
        !isRunning(fromShift, to)
    } yield {
      (fromShift, to, pendingTasks(fromShift)(to))
    }
  }

  protected def startNewTasks(): Unit = {
    pickTasks().foreach {
      case (fromShift, to, template) =>
        val index        = ChainIndex.unsafe(fromShift + brokerConfig.groupFrom, to)
        val blockHandler = handlers.getBlockHandler(index)
        startTask(fromShift, to, template, blockHandler)
        setRunning(fromShift, to)
    }
  }

  protected def postMinerStop(): Unit = {
    for {
      fromShift <- 0 until brokerConfig.groupNumPerBroker
      to        <- 0 until brokerConfig.groups
    } setIdle(fromShift, to)
  }

  def prepareTemplate(fromShift: Int, to: Int): BlockTemplate

  def startTask(fromShift: Int,
                to: Int,
                template: BlockTemplate,
                blockHandler: ActorRefT[BlockChainHandler.Command]): Unit
}
