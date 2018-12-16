package org.alephium.flow.storage

import akka.actor.ActorSystem
import akka.testkit.TestProbe
import org.alephium.flow.PlatformConfig

object HandlerUtils {

  def createBlockHandlersProbe(implicit config: PlatformConfig,
                               system: ActorSystem): BlockHandlers = {
    val blockHandler  = TestProbe()
    val chainHandlers = Seq.tabulate(config.groups, config.groups)((_, _) => TestProbe().ref)
    BlockHandlers(blockHandler.ref, chainHandlers)
  }
}
