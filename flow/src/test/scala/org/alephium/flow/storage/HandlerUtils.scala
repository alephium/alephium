package org.alephium.flow.storage

import akka.actor.ActorSystem
import akka.testkit.TestProbe
import org.alephium.flow.PlatformConfig
import org.alephium.util.AVector

object HandlerUtils {

  def createBlockHandlersProbe(implicit config: PlatformConfig,
                               system: ActorSystem): BlockHandlers = {
    val blockHandler  = TestProbe()
    val chainHandlers = AVector.tabulate(config.groups, config.groups)((_, _) => TestProbe().ref)
    BlockHandlers(blockHandler.ref, chainHandlers)
  }
}
