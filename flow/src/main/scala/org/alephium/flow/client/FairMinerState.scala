package org.alephium.flow.client

import akka.actor.ActorRef

import org.alephium.flow.PlatformConfig
import org.alephium.flow.model.BlockTemplate
import org.alephium.flow.storage.AllHandlers
import org.alephium.protocol.model.ChainIndex

trait FairMinerState {
  implicit def config: PlatformConfig

  def handlers: AllHandlers

  protected val miningCounts = Array.fill[BigInt](config.groupNumPerBroker, config.groups)(0)
  protected val running      = Array.fill(config.groupNumPerBroker, config.groups)(false)
  protected lazy val pendingTasks =
    Array.tabulate(config.groupNumPerBroker, config.groups)(prepareTemplate)

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
      fromShift <- 0 until config.groupNumPerBroker
      to        <- 0 until config.groups
    } {
      val blockTemplate = prepareTemplate(fromShift, to)
      pendingTasks(fromShift)(to) = blockTemplate
    }
  }

  protected def pickTasks(): IndexedSeq[(Int, Int, BlockTemplate)] = {
    val minCount = miningCounts.map(_.min).min
    for {
      fromShift <- 0 until config.groupNumPerBroker
      to        <- 0 until config.groups
      if {
        (miningCounts(fromShift)(to) <= minCount + config.nonceStep) && !isRunning(fromShift, to)
      }
    } yield {
      (fromShift, to, pendingTasks(fromShift)(to))
    }
  }

  protected def startNewTasks(): Unit = {
    pickTasks().foreach {
      case (fromShift, to, template) =>
        val index        = ChainIndex.unsafe(fromShift + config.brokerInfo.groupFrom, to)
        val blockHandler = handlers.getBlockHandler(index)
        startTask(fromShift, to, template, blockHandler)
        setRunning(fromShift, to)
    }
  }

  def prepareTemplate(fromShift: Int, to: Int): BlockTemplate

  def startTask(fromShift: Int, to: Int, template: BlockTemplate, blockHandler: ActorRef): Unit
}
