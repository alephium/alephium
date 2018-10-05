package org.alephium.mock

import akka.actor.{ActorRef, Props, Timers}
import org.alephium.client.{Miner, Node}
import org.alephium.constant.Consensus
import org.alephium.crypto.{ED25519PublicKey, Keccak256}
import org.alephium.protocol.model.{Block, Transaction}
import org.alephium.storage.BlockFlow.ChainIndex
import org.alephium.storage.ChainHandler.BlockOrigin.Local
import org.alephium.storage.{AddBlockResult, ChainHandler, FlowHandler}

import scala.annotation.tailrec
import scala.concurrent.duration._
import scala.util.Random

object MockMiner {

  object Timer
  case class MockMining(timestamp: Long)

  trait Builder extends Miner.Builder {
    override def createMiner(address: ED25519PublicKey, node: Node, chainIndex: ChainIndex): Props =
      Props(new MockMiner(address, node, chainIndex))
  }
}

class MockMiner(address: ED25519PublicKey, node: Node, chainIndex: ChainIndex)
    extends Miner(address, node, chainIndex)
    with Timers {
  import node.blockHandlers

  val chainHandler: ActorRef = blockHandlers.getHandler(chainIndex)

  override def _mine(deps: Seq[Keccak256], transactions: Seq[Transaction], lastTs: Long): Receive = {
    case Miner.Nonce(_, _) =>
      val delta     = 1000 * 30 + Random.nextInt(1000 * 60)
      val currentTs = System.currentTimeMillis()
      val nextTs =
        if (lastTs == 0) currentTs + delta
        else {
          val num = (currentTs - lastTs) / delta + 1
          if (num > 1) log.info(s"---- step: $num")
          lastTs + num * delta
        }
      val sleepTs = nextTs - currentTs
      timers.startSingleTimer(MockMiner.Timer, MockMiner.MockMining(nextTs), sleepTs.millis)

    case MockMiner.MockMining(nextTs) =>
      val block = tryMine(deps, Seq.empty, nextTs, Long.MaxValue).get
      log.info(s"A new block ${block.shortHash} is mined at ${block.blockHeader.timestamp}")
      chainHandler ! ChainHandler.AddBlocks(Seq(block), Local)

    case _: AddBlockResult =>
      blockHandlers.flowHandler ! FlowHandler.PrepareBlockFlow(chainIndex)
      context become collect
  }

  override def tryMine(deps: Seq[Keccak256],
                       transactions: Seq[Transaction],
                       nextTs: BigInt,
                       to: BigInt): Option[Block] = {
    @tailrec
    def iter(current: BigInt): Option[Block] = {
      if (current < to) {
        val timestamp = System.currentTimeMillis()
        val block     = Block.from(deps, timestamp, Consensus.maxMiningTarget, current)
        if (isDifficult(block)) Some(block)
        else iter(current + 1)
      } else None
    }

    iter(Random.nextInt)
  }

  override def isDifficult(block: Block): Boolean = {
    chainIndex.accept(block.hash)
  }
}
