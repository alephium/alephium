package org.alephium.flow.client

import akka.actor.ActorRef
import org.alephium.flow.PlatformConfig
import org.alephium.flow.model.BlockTemplate
import org.alephium.util.AVector

import scala.concurrent.duration._

trait FairMinerState {
  implicit def config: PlatformConfig

  protected def actualMiners: AVector[AVector[ActorRef]]

  protected val miningCounts        = Array.fill[BigInt](config.groupNumPerBroker, config.groups)(0)
  protected val taskRefreshDuration = config.groups.seconds.toMillis
  protected val taskRefreshTss      = Array.fill[Long](config.groupNumPerBroker, config.groups)(-1)
  protected val pendingTasks        = collection.mutable.Map.empty[(Int, Int), BlockTemplate]

  def initialize(): Unit = {
    for {
      fromShift <- 0 until config.groupNumPerBroker
      to        <- 0 until config.groups
    } {
      prepareTemplate(fromShift, to)
    }
  }

  def getMiningCount(fromShift: Int, to: Int): BigInt = miningCounts(fromShift)(to)

  def countsToString: String = {
    miningCounts.map(_.mkString(",")).mkString(",")
  }

  def increaseCounts(fromShift: Int, to: Int, count: BigInt): Unit = {
    miningCounts(fromShift)(to) += count
  }

  def refreshLastTask(fromShift: Int, to: Int, template: BlockTemplate): Unit = {
    assert(0 <= fromShift && fromShift < config.groupNumPerBroker && 0 <= to && to < config.groups)
    val lastRefreshTs = taskRefreshTss(fromShift)(to)
    val currentTs     = System.currentTimeMillis()
    if (currentTs - lastRefreshTs > taskRefreshDuration) {
      prepareTemplate(fromShift, to)
    } else {
      addTask(fromShift, to, template)
    }
  }

  def addNewTask(fromShift: Int, to: Int, template: BlockTemplate): Unit = {
    taskRefreshTss(fromShift)(to) = System.currentTimeMillis()
    addTask(fromShift, to, template)
  }

  def addTask(from: Int, to: Int, template: BlockTemplate): Unit = {
    assert(!pendingTasks.contains((from, to)))
    pendingTasks((from, to)) = template
    startNewTasks()
  }

  protected def pickTasks(): Iterable[((Int, Int), BlockTemplate)] = {
    val minCount = miningCounts.map(_.min).min
    val toTries = pendingTasks.keys.filter {
      case (fromShift, to) => miningCounts(fromShift)(to) < minCount + config.nonceStep
    }
    toTries.map { key =>
      val template = pendingTasks(key)
      pendingTasks -= key
      (key, template)
    }
  }

  protected def startNewTasks(): Unit = {
    pickTasks().foreach {
      case ((fromShift, to), template) => startTask(fromShift, to, template)
    }
  }

  def prepareTemplate(fromShift: Int, to: Int): Unit

  def startTask(fromShift: Int, to: Int, template: BlockTemplate): Unit
}
