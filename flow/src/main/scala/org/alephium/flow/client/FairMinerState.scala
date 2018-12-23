package org.alephium.flow.client

import akka.actor.ActorRef
import org.alephium.flow.PlatformConfig
import org.alephium.flow.model.BlockTemplate
import org.alephium.util.AVector

import scala.concurrent.duration._

trait FairMinerState {
  implicit def config: PlatformConfig

  protected def actualMiners: AVector[ActorRef]

  protected val miningCounts        = Array.fill[BigInt](config.groups)(0)
  protected val taskRefreshDuration = config.groups.seconds.toMillis
  protected val taskRefreshTss      = Array.fill[Long](config.groups)(-1)
  protected val pendingTasks        = collection.mutable.Map.empty[Int, BlockTemplate]

  def initialize(): Unit = {
    (0 until config.groups).foreach(prepareTemplate)
  }

  def getMiningCount(to: Int): BigInt = miningCounts(to)

  def increaseCounts(to: Int, count: BigInt): Unit = {
    miningCounts(to) += count
  }

  def refreshLastTask(to: Int, template: BlockTemplate): Unit = {
    assert(to >= 0 && to < config.groups)
    val lastRefreshTs = taskRefreshTss(to)
    val currentTs     = System.currentTimeMillis()
    if (currentTs - lastRefreshTs > taskRefreshDuration) {
      prepareTemplate(to)
    } else {
      addTask(to, template)
    }
  }

  def addNewTask(to: Int, template: BlockTemplate): Unit = {
    taskRefreshTss(to) = System.currentTimeMillis()
    addTask(to, template)
  }

  def addTask(to: Int, template: BlockTemplate): Unit = {
    assert(!pendingTasks.contains(to))
    pendingTasks(to) = template
    startNewTasks()
  }

  protected def pickTasks(): Iterable[(Int, BlockTemplate)] = {
    val minCount = miningCounts.min
    val toTries  = pendingTasks.keys.filter(to => miningCounts(to) < minCount + config.nonceStep)
    toTries.map { to =>
      val template = pendingTasks(to)
      pendingTasks -= to
      (to, template)
    }
  }

  protected def startNewTasks(): Unit = {
    pickTasks().foreach {
      case (to, template) => startTask(to, template)
    }
  }

  def prepareTemplate(to: Int): Unit

  def startTask(to: Int, template: BlockTemplate): Unit
}
