package org.alephium.flow.storage

import akka.actor.ActorSystem
import akka.testkit.TestProbe
import org.alephium.flow.PlatformConfig
import org.alephium.protocol.model.ChainIndex

object HandlerUtils {

  def createBlockHandlersProbe(implicit config: PlatformConfig,
                               system: ActorSystem): AllHandlers = {
    val flowHandler = TestProbe().ref
    val blockHandlers = (for {
      from <- 0 until config.groups
      to   <- 0 until config.groups
      if from == config.groups || to == config.groups
    } yield {
      ChainIndex(from, to) -> TestProbe().ref
    }).toMap
    val headerHandlers = (for {
      from <- 0 until config.groups
      to   <- 0 until config.groups
      if from != config.groups && to != config.groups
    } yield {
      ChainIndex(from, to) -> TestProbe().ref
    }).toMap
    AllHandlers(flowHandler, blockHandlers, headerHandlers)
  }
}
