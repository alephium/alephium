package org.alephium.storage

import java.math.BigInteger

import akka.actor.{ActorRef, Props}
import org.alephium.{AlephiumActorSpec, Fixture}
import org.alephium.protocol.Genesis
import org.alephium.crypto.ED25519PublicKey
import org.alephium.protocol.model.{Block, ModelGen}

class BlockPoolSpec extends AlephiumActorSpec("block_pool_spec") with Fixture {
  import BlockPool._

  private val genesis: Block        = Genesis.block
  private val blockPoolProps: Props = BlockPool.props()

  behavior of "BlockPool"

  it should "contains genesis block in the beginning" in {
    val pool = system.actorOf(blockPoolProps)
    pool ! GetBestChain
    expectMsg(BestChain(Seq(genesis)))
  }

  it should "add a new block and increase the chain length" in {
    forAll(ModelGen.blockGenWith(Seq(genesis.hash))) { block =>
      val pool = system.actorOf(blockPoolProps)
      addBlocks(pool, block)
      pool ! GetBestHeader
      expectMsg(BestHeader(block))
      pool ! GetBestChain
      expectMsg(BestChain(Seq(genesis, block)))
    }
  }

  it should "add a side block and keep the chain length" in {
    forAll(ModelGen.blockGen) { block =>
      val pool = system.actorOf(blockPoolProps)
      addBlocks(pool, block)
      pool ! GetBestChain
      expectMsgAnyOf(BestChain(Seq(genesis)), BestChain(Seq(block)))
    }
  }

  it should "add two sequential blocks and increase the chain length" in {
    forAll(ModelGen.blockGenWith(Seq(genesis.hash))) { block1 =>
      forAll(ModelGen.blockGenWith(Seq(block1.hash))) { block2 =>
        val pool = system.actorOf(blockPoolProps)
        addBlocks(pool, block1, block2)
        pool ! GetBestHeader
        expectMsg(BestHeader(block2))
        pool ! GetBestChain
        expectMsg(BestChain(Seq(genesis, block1, block2)))
      }
    }
  }

  it should "return correct balance with only genesis block" in {
    val pool = system.actorOf(blockPoolProps)

    pool ! GetBalance(testPublicKey)
    expectMsg(Balance(testPublicKey, genesis, testBalance))
    pool ! GetBalance(ED25519PublicKey.zero)
    expectMsg(Balance(ED25519PublicKey.zero, genesis, BigInteger.ZERO))
  }

  it should "return correct balance after transfering money" in {
    val pool     = system.actorOf(blockPoolProps)
    val newBlock = blockForTransfer(ED25519PublicKey.zero, BigInteger.valueOf(10l))

    addBlocks(pool, newBlock)

    pool ! GetBestHeader
    expectMsg(BestHeader(newBlock))

    pool ! GetBestChain
    expectMsg(BestChain(Seq(genesis, newBlock)))

    pool ! GetBalance(testPublicKey)
    expectMsg(Balance(testPublicKey, newBlock, testBalance subtract BigInteger.valueOf(10l)))

    pool ! GetBalance(ED25519PublicKey.zero)
    expectMsg(Balance(ED25519PublicKey.zero, newBlock, BigInteger.valueOf(10l)))
  }

  it should "return correct utxos with only genesis block" in {
    val pool = system.actorOf(blockPoolProps)

    pool ! GetUTXOs(testPublicKey, testBalance)
    expectMsgPF() {
      case UTXOs(header, inputs, total) =>
        header shouldBe genesis.hash
        inputs.size shouldBe 1
        total shouldBe testBalance
    }

    pool ! GetUTXOs(testPublicKey, testBalance add BigInteger.ONE)
    expectMsg(NoEnoughBalance)

    pool ! GetUTXOs(ED25519PublicKey.zero, BigInteger.valueOf(10l))
    expectMsg(NoEnoughBalance)
  }

  it should "return correct utxos after transfering money" in {
    val pool     = system.actorOf(blockPoolProps)
    val newBlock = blockForTransfer(ED25519PublicKey.zero, BigInteger.valueOf(10l))
    addBlocks(pool, newBlock)

    pool ! GetUTXOs(testPublicKey, BigInteger.valueOf(10l))
    expectMsgPF() {
      case UTXOs(header, inputs, total) =>
        header shouldBe newBlock.hash
        inputs.size shouldBe 1
        total shouldBe (testBalance subtract BigInteger.valueOf(10l))
    }

    pool ! GetUTXOs(testPublicKey, BigInteger.valueOf(100l))
    expectMsg(NoEnoughBalance)

    pool ! GetUTXOs(ED25519PublicKey.zero, BigInteger.valueOf(10l))
    expectMsgType[UTXOs]
    pool ! GetUTXOs(ED25519PublicKey.zero, BigInteger.valueOf(11l))
    expectMsg(NoEnoughBalance)
  }

  private def addBlocks(pool: ActorRef, blocks: Block*) = {
    pool ! AddBlocks(blocks)
  }
}
