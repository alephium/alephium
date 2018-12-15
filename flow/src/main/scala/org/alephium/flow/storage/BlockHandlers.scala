package org.alephium.flow.storage

import akka.actor.ActorRef
import org.alephium.protocol.model.ChainIndex
import org.alephium.util.AVector

/*
 * @globalHandlers: actor of BlockHandler
 * @poolHandlers: actors of BlockPoolHandler
 */
case class BlockHandlers(flowHandler: ActorRef, chainHandlers: AVector[AVector[ActorRef]]) {
  def getHandler(chainIndex: ChainIndex): ActorRef = chainHandlers(chainIndex.from)(chainIndex.to)
}
