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
      if {
        (miningCounts(fromShift)(to) <= minCount + miningConfig.nonceStep) && !isRunning(fromShift,
                                                                                         to)
      }
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

  def prepareTemplate(fromShift: Int, to: Int): BlockTemplate

  def startTask(fromShift: Int,
                to: Int,
                template: BlockTemplate,
                blockHandler: ActorRefT[BlockChainHandler.Command]): Unit
}
