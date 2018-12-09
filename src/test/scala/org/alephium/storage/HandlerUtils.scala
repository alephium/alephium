package org.alephium.storage

import akka.actor.ActorSystem
import akka.testkit.TestProbe
import org.alephium.constant.Network

object HandlerUtils {

  def createBlockHandlersProbe(implicit system: ActorSystem): BlockHandlers = {
    val blockHandler = TestProbe()
    val poolHandlers = Seq.tabulate(Network.groups, Network.groups)((_, _) => TestProbe().ref)
    BlockHandlers(blockHandler.ref, poolHandlers)
  }
}
