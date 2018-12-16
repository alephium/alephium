package org.alephium.flow.storage

import akka.actor.ActorRef
import org.alephium.flow.PlatformConfig
import org.alephium.protocol.model.ChainIndex

/*
 * @globalHandlers: actor of BlockHandler
 * @poolHandlers: actors of BlockPoolHandler
 */
case class AllHandlers(flowHandler: ActorRef,
                       blockHandlers: Map[ChainIndex, ActorRef],
                       headerHandlers: Map[ChainIndex, ActorRef])(implicit config: PlatformConfig) {

  def getBlockHandler(chainIndex: ChainIndex): ActorRef = {
    assert(chainIndex.from == config.mainGroup || chainIndex.to == config.mainGroup)
    blockHandlers(chainIndex)
  }

  def getHeaderHandler(chainIndex: ChainIndex): ActorRef = {
    assert(chainIndex.from != config.mainGroup && chainIndex.to != config.mainGroup)
    headerHandlers(chainIndex)
  }
}
