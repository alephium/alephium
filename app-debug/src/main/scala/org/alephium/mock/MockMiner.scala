package org.alephium.mock

import akka.actor.{Props, Timers}
import org.alephium.crypto.ED25519PublicKey
import org.alephium.flow.PlatformConfig
import org.alephium.flow.client.{Miner, Node}
import org.alephium.flow.model.{BlockTemplate, ChainIndex}
import org.alephium.flow.storage.ChainHandler.BlockOrigin.Local
import org.alephium.flow.storage.{AddBlockResult, ChainHandler, FlowHandler}
import org.alephium.protocol.model.Block
import org.alephium.util.AVector

import scala.annotation.tailrec
import scala.concurrent.duration._
import scala.util.Random

object MockMiner {

  object Timer
  case class MockMining(timestamp: Long)

  trait Builder extends Miner.Builder {
    override def createMiner(address: ED25519PublicKey, node: Node, chainIndex: ChainIndex)(
        implicit config: PlatformConfig): Props =
      Props(new MockMiner(address, node, chainIndex))
  }
}

class MockMiner(address: ED25519PublicKey, node: Node, chainIndex: ChainIndex)(
    implicit config: PlatformConfig)
    extends Miner(address, node, chainIndex)
    with Timers {
  import node.blockHandlers

  override def _mine(template: BlockTemplate, lastTs: Long): Receive = {
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
      val block = tryMine(template, nextTs, Long.MaxValue).get
      log.info(s"A new block ${block.shortHash} is mined at ${block.blockHeader.timestamp}")
      chainHandler ! ChainHandler.AddBlocks(AVector(block), Local)

    case AddBlockResult.Success =>
      blockHandlers.flowHandler ! FlowHandler.PrepareBlockFlow(chainIndex)
      context become collect

    case _: AddBlockResult.Failure =>
      context stop self
  }

  override def tryMine(template: BlockTemplate, nextTs: BigInt, to: BigInt): Option[Block] = {
    @tailrec
    def iter(current: BigInt): Option[Block] = {
      if (current < to) {
        val timestamp = System.currentTimeMillis()
        val block     = Block.from(template.deps, timestamp, template.target, current)
        if (isDifficult(block)) Some(block)
        else iter(current + 1)
      } else None
    }

    iter(Random.nextInt)
  }
}
