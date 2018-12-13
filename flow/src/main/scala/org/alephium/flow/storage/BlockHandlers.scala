package org.alephium.flow.storage
import akka.actor.ActorRef
import org.alephium.flow.model.ChainIndex

/*
 * @globalHandlers: actor of BlockHandler
 * @poolHandlers: actors of BlockPoolHandler
 */
case class BlockHandlers(flowHandler: ActorRef, chainHandlers: Seq[Seq[ActorRef]]) {
  def getHandler(chainIndex: ChainIndex): ActorRef = chainHandlers(chainIndex.from)(chainIndex.to)
}
