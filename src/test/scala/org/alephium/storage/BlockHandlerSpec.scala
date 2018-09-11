package org.alephium.storage

import java.net.InetSocketAddress

import akka.actor.{ActorRef, Props}
import akka.testkit.SocketUtil
import org.alephium.{AlephiumActorSpec, TxFixture}
import org.alephium.protocol.Genesis
import org.alephium.network.PeerManager
import org.alephium.protocol.model.{Block, ModelGen}

class BlockHandlerSpec extends AlephiumActorSpec("block_handler_spec") with TxFixture {
  import BlockHandler._

  private val genesis: Block            = Genesis.block
  private val blockFlow: BlockFlow      = BlockFlow()
  private val blockHandlerProps: Props  = BlockHandler.props(blockFlow)
  private val remote: InetSocketAddress = new InetSocketAddress(SocketUtil.temporaryLocalPort())

  private def createBlockHandler(): ActorRef = {
    system.actorOf(blockHandlerProps)
  }

  private def addBlocks(pool: ActorRef, blocks: Block*) = {
    pool ! AddBlocks(blocks)
  }

  behavior of "BlockPoolHandler"

  it should "contains genesis block in the beginning" in {
    val blockHandler = createBlockHandler()
    blockHandler ! GetBestChain
    expectMsg(BestChain(Seq(genesis)))
  }

  it should "add a new block and increase the chain length" in {
    forAll(ModelGen.blockGenWith(Seq(genesis.hash))) { block =>
      val blockHandler = createBlockHandler()
      addBlocks(blockHandler, block)
      blockHandler ! GetBestHeader
      expectMsg(BestHeader(block))
      blockHandler ! GetBestChain
      expectMsg(BestChain(Seq(genesis, block)))
      blockHandler ! PrepareSync(remote)
      expectMsg(PeerManager.Sync(remote, Seq(block.hash)))
    }
  }

  it should "add a side block and keep the chain length" in {
    forAll(ModelGen.blockGen) { block =>
      val blockHandler = createBlockHandler()
      addBlocks(blockHandler, block)
      blockHandler ! GetBestChain
      expectMsgAnyOf(BestChain(Seq(genesis)), BestChain(Seq(block)))
    }
  }

  it should "add two sequential blocks and increase the chain length" in {
    forAll(ModelGen.blockGenWith(Seq(genesis.hash))) { block1 =>
      forAll(ModelGen.blockGenWith(Seq(block1.hash))) { block2 =>
        val blockHandler = createBlockHandler()
        addBlocks(blockHandler, block1, block2)
        blockHandler ! GetBestHeader
        expectMsg(BestHeader(block2))
        blockHandler ! GetBestChain
        expectMsg(BestChain(Seq(genesis, block1, block2)))
        blockHandler ! PrepareSync(remote)
        expectMsg(PeerManager.Sync(remote, Seq(block2.hash)))
      }
    }
  }
}
