package org.alephium.mock

import scala.annotation.tailrec
import scala.util.Random

import akka.actor.Props

import org.alephium.crypto.ED25519PublicKey
import org.alephium.flow.client.{Miner, Node}
import org.alephium.flow.core.{BlockChainHandler, FlowHandler}
import org.alephium.flow.core.validation.Validation
import org.alephium.flow.model.BlockTemplate
import org.alephium.flow.model.DataOrigin.Local
import org.alephium.flow.platform.PlatformProfile
import org.alephium.protocol.model.{Block, ChainIndex}
import org.alephium.util.{Duration, TimeStamp}

object MockMiner {

  final case class MockMining(timestamp: TimeStamp)

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

  @SuppressWarnings(Array("org.wartremover.warts.OptionPartial"))
  override def _mine(template: BlockTemplate, lastTs: TimeStamp): Receive = {
    case Miner.Nonce(_, _) =>
      val delta     = Duration.unsafe(1000l * 30l + Random.nextInt(1000 * 60).toLong)
      val currentTs = TimeStamp.now()
      val nextTs =
        if (lastTs == TimeStamp.zero) currentTs + delta
        else {
          val num = (currentTs.millis - lastTs.millis) / delta.millis + 1
          if (num > 1) log.info(s"---- step: $num")
          lastTs + delta.timesUnsafe(num)
        }
      val sleepTs = (nextTs -- currentTs).get
      scheduleOnce(self, MockMiner.MockMining(nextTs), sleepTs)

    case MockMiner.MockMining(nextTs) =>
      val block = tryMine(template, nextTs.millis, Long.MaxValue).get
      log.info(s"A new block ${block.shortHex} got mined at ${block.header.timestamp}")
      blockHandler ! BlockChainHandler.addOneBlock(block, Local)

    case Miner.UpdateTemplate =>
      allHandlers.flowHandler ! FlowHandler.PrepareBlockFlow(chainIndex)
      context become collect

    case Miner.MinedBlockAdded(_) =>
      allHandlers.flowHandler ! FlowHandler.PrepareBlockFlow(chainIndex)
      context become collect
  }

  override def tryMine(template: BlockTemplate, from: BigInt, to: BigInt): Option[Block] = {
    @tailrec
    def iter(current: BigInt): Option[Block] = {
      if (current < to) {
        val header = template.buildHeader(current)
        if (Validation.validateMined(header, chainIndex))
          Some(Block(header, template.transactions))
        else iter(current + 1)
      } else None
    }

    iter(from)
  }
}
