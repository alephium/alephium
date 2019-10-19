package org.alephium.mock

import scala.annotation.tailrec
import scala.concurrent.duration._
import scala.util.Random

import akka.actor.Props

import org.alephium.crypto.ED25519PublicKey
import org.alephium.flow.client.{Miner, Node}
import org.alephium.flow.core.{BlockChainHandler, FlowHandler}
import org.alephium.flow.model.BlockTemplate
import org.alephium.flow.model.DataOrigin.LocalMining
import org.alephium.flow.platform.PlatformProfile
import org.alephium.protocol.model.{Block, ChainIndex}

object MockMiner {

  case class MockMining(timestamp: Long)

  trait Builder extends Miner.Builder {
    override def createMiner(address: ED25519PublicKey, node: Node, chainIndex: ChainIndex)(
        implicit config: PlatformProfile): Props =
      Props(new MockMiner(address, node, chainIndex))
  }
}

class MockMiner(address: ED25519PublicKey, node: Node, chainIndex: ChainIndex)(
    implicit config: PlatformProfile)
    extends Miner(address, node, chainIndex) {
  import node.allHandlers

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
      scheduleOnce(self, MockMiner.MockMining(nextTs), sleepTs.millis)

    case MockMiner.MockMining(nextTs) =>
      val block = tryMine(template, nextTs, Long.MaxValue).get
      log.info(s"A new block ${block.shortHex} got mined at ${block.header.timestamp}")
      blockHandler ! BlockChainHandler.AddBlock(block, LocalMining)

    case Miner.UpdateTemplate =>
      allHandlers.flowHandler ! FlowHandler.PrepareBlockFlow(chainIndex)
      context become collect

    case Miner.MinedBlockAdded(_) =>
      allHandlers.flowHandler ! FlowHandler.PrepareBlockFlow(chainIndex)
      context become collect
  }

  override def tryMine(template: BlockTemplate, nextTs: BigInt, to: BigInt): Option[Block] = {
    @tailrec
    def iter(current: BigInt): Option[Block] = {
      if (current < to) {
        val header = template.buildHeader(current)
        if (header.preValidate(chainIndex))
          Some(Block(header, template.transactions))
        else iter(current + 1)
      } else None
    }

    iter(Random.nextInt)
  }
}
