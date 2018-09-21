package org.alephium.storage
import akka.actor.ActorRef
import org.alephium.storage.BlockFlow.ChainIndex

/*
 * @globalHandlers: actor of BlockHandler
 * @poolHandlers: actors of BlockPoolHandler
 */
case class BlockHandlers(globalHandler: ActorRef, chainHandlers: Seq[Seq[ActorRef]]) {
  def getHandler(chainIndex: ChainIndex): ActorRef = chainHandlers(chainIndex.from)(chainIndex.to)
}
