package org.alephium.network

import akka.actor.{ActorRef, Props}
import org.alephium.protocol.model.Block
import org.alephium.storage.BlockPool
import org.alephium.util.BaseActor

object BlockHandler {
  def props(blockPool: ActorRef): Props = Props(new BlockHandler(blockPool))

  sealed trait Command
  case class AddBlock(block: Block) extends Command
}

class BlockHandler(blockPool: ActorRef) extends BaseActor {
  import BlockHandler._

  override def receive: Receive = {
    case AddBlock(block) =>
      blockPool ! BlockPool.AddBlocks(Seq(block))
  }
}
