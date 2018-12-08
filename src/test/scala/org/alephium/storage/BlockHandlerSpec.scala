package org.alephium.storage

import java.net.InetSocketAddress

import akka.actor.{ActorRef, Props}
import akka.testkit.TestProbe
import org.alephium.{AlephiumActorSpec, TxFixture}
import org.alephium.protocol.Genesis
import org.alephium.crypto.ED25519PublicKey
import org.alephium.network.PeerManager
import org.alephium.protocol.model.{Block, ModelGen}

class BlockHandlerSpec extends AlephiumActorSpec("block_handler_spec") with TxFixture {
  import BlockHandler._

  private val genesis: Block            = Genesis.block
  private val blockHandlerProps: Props  = BlockHandler.props()
  private val remote: InetSocketAddress = new InetSocketAddress(1000)
  private val peerManager: TestProbe    = TestProbe()

  private def createBlockHandler(): ActorRef = {
    val blockHandler = system.actorOf(blockHandlerProps)
    blockHandler.tell(PeerManager.Hello, peerManager.ref)
    blockHandler
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

  it should "return correct balance with only genesis block" in {
    val blockHandler = createBlockHandler()

    blockHandler ! GetBalance(testPublicKey)
    expectMsg(Balance(testPublicKey, genesis, testBalance))
    blockHandler ! GetBalance(ED25519PublicKey.zero)
    expectMsg(Balance(ED25519PublicKey.zero, genesis, 0))
  }

  it should "return correct balance after transferring money" in {
    val blockHandler = createBlockHandler()
    val newBlock     = blockForTransfer(ED25519PublicKey.zero, 10)

    addBlocks(blockHandler, newBlock)

    blockHandler ! GetBestHeader
    expectMsg(BestHeader(newBlock))

    blockHandler ! GetBestChain
    expectMsg(BestChain(Seq(genesis, newBlock)))

    blockHandler ! GetBalance(testPublicKey)
    expectMsg(Balance(testPublicKey, newBlock, testBalance - 10))

    blockHandler ! GetBalance(ED25519PublicKey.zero)
    expectMsg(Balance(ED25519PublicKey.zero, newBlock, 10))
  }

  it should "return correct utxos with only genesis block" in {
    val blockHandler = createBlockHandler()

    blockHandler ! GetUTXOs(testPublicKey, testBalance)
    expectMsgPF() {
      case UTXOs(header, inputs, total) =>
        header is genesis.hash
        inputs.size is 1
        total is testBalance
    }

    blockHandler ! GetUTXOs(testPublicKey, testBalance + 1)
    expectMsg(NoEnoughBalance)

    blockHandler ! GetUTXOs(ED25519PublicKey.zero, 10)
    expectMsg(NoEnoughBalance)
  }

  it should "return correct utxos after transfering money" in {
    val blockHandler = createBlockHandler()
    val newBlock     = blockForTransfer(ED25519PublicKey.zero, 10)
    addBlocks(blockHandler, newBlock)

    blockHandler ! GetUTXOs(testPublicKey, 10)
    expectMsgPF() {
      case UTXOs(header, inputs, total) =>
        header is newBlock.hash
        inputs.size is 1
        total is (testBalance - 10)
    }

    blockHandler ! GetUTXOs(testPublicKey, 100)
    expectMsg(NoEnoughBalance)

    blockHandler ! GetUTXOs(ED25519PublicKey.zero, 10)
    expectMsgType[UTXOs]
    blockHandler ! GetUTXOs(ED25519PublicKey.zero, 11)
    expectMsg(NoEnoughBalance)
  }
}
