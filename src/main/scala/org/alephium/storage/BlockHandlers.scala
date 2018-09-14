package org.alephium.storage
import akka.actor.ActorRef
import org.alephium.storage.BlockFlow.ChainIndex

/*
 * @globalHandlers: actor of BlockHandler
 * @poolHandlers: actors of BlockPoolHandler
 */
case class BlockHandlers(globalHandler: ActorRef, poolHandlers: Seq[Seq[ActorRef]]) {
  def getHandler(chainIndex: ChainIndex): ActorRef = poolHandlers(chainIndex.from)(chainIndex.to)
}
