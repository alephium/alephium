package org.alephium.flow.storage

import akka.actor.ActorSystem
import akka.testkit.TestProbe
import org.alephium.flow.constant.Network

object HandlerUtils {

  def createBlockHandlersProbe(implicit system: ActorSystem): BlockHandlers = {
    val blockHandler  = TestProbe()
    val chainHandlers = Seq.tabulate(Network.groups, Network.groups)((_, _) => TestProbe().ref)
    BlockHandlers(blockHandler.ref, chainHandlers)
  }
}
