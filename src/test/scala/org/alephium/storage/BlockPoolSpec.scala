package org.alephium.storage

import java.math.BigInteger

import org.alephium.{AlephiumSpec, Fixture}
import org.alephium.protocol.Genesis
import org.alephium.crypto.ED25519PublicKey
import org.alephium.protocol.model.{Block, ModelGen, TxInput}

class BlockPoolSpec extends AlephiumSpec with Fixture {
  val genesis: Block = Genesis.block

  behavior of "BlockPool"

  it should "contains genesis block in the beginning" in {
    val pool  = BlockPool()
    val chain = pool.getBestChain
    chain.size shouldBe 1
    val block = chain.head
    block shouldBe genesis
  }

  it should "add a new block and increase the chain length" in {
    forAll(ModelGen.blockGenWith(Seq(genesis.hash))) { block =>
      val pool = BlockPool()
      pool.addBlock(block)
      pool.getBestHeader shouldBe block
      val chain = pool.getBestChain
      chain.size shouldBe 2
      chain.last shouldBe block
    }
  }

  it should "add a side block and keep the chain length" in {
    forAll(ModelGen.blockGen) { block =>
      val pool = BlockPool()
      pool.addBlock(block)
      val chain = pool.getBestChain
      chain.size shouldBe 1
    }
  }

  it should "add two sequential blocks and increase the chain length" in {
    forAll(ModelGen.blockGenWith(Seq(genesis.hash))) { block1 =>
      forAll(ModelGen.blockGenWith(Seq(block1.hash))) { block2 =>
        val pool = BlockPool()
        pool.addBlocks(Seq(block1, block2))
        pool.getBestHeader shouldBe block2
        val chain = pool.getBestChain
        chain.size shouldBe 3
        chain(1) shouldBe block1
        chain(2) shouldBe block2
      }
    }
  }

  it should "return correct balance with only genesis block" in {
    val pool = BlockPool()

    val (block1, balance1) = pool.getBalance(testPublicKey)
    block1 shouldBe genesis
    balance1 shouldBe testBalance
    val (block2, balance2) = pool.getBalance(ED25519PublicKey.zero)
    block2 shouldBe genesis
    balance2 shouldBe BigInteger.ZERO
  }

  it should "return correct balance after transfering money" in {
    val pool     = BlockPool()
    val newBlock = blockForTransfer(ED25519PublicKey.zero, BigInteger.valueOf(10l))
    pool.addBlock(newBlock)

    val (block1, balance1) = pool.getBalance(testPublicKey)
    block1 shouldBe newBlock
    balance1 shouldBe (testBalance subtract BigInteger.valueOf(10l))
    val (block2, balance2) = pool.getBalance(ED25519PublicKey.zero)
    block2 shouldBe newBlock
    balance2 shouldBe BigInteger.valueOf(10l)
  }

  it should "return correct utxos with only genesis block" in {
    val pool = BlockPool()

    val utxos1 = pool.getUTXOs(testPublicKey)
    utxos1.size shouldBe 1
    utxos1.head shouldBe TxInput(Genesis.transactions.head.hash, 0)
    val utxos2 = pool.getUTXOs(ED25519PublicKey.zero)
    utxos2.size shouldBe 0

    val utxos3 = pool.getUTXOs(testPublicKey, testBalance)
    utxos3 shouldBe defined
    utxos3.get._2 shouldBe testBalance
    val utxos4 = pool.getUTXOs(testPublicKey, testBalance add BigInteger.ONE)
    utxos4 shouldBe None

    val utxos5 = pool.getUTXOs(ED25519PublicKey.zero, BigInteger.valueOf(10l))
    utxos5 shouldBe None
  }

  it should "return correct utxos after transfering money" in {
    val pool     = BlockPool()
    val newBlock = blockForTransfer(ED25519PublicKey.zero, BigInteger.valueOf(10l))
    pool.addBlock(newBlock)

    val utxos1 = pool.getUTXOs(testPublicKey)
    utxos1.size shouldBe 1
    val utxos2 = pool.getUTXOs(ED25519PublicKey.zero)
    utxos2.size shouldBe 1

    val utxos3 = pool.getUTXOs(testPublicKey, BigInteger.valueOf(10l))
    utxos3 shouldBe defined
    utxos3.get._2 shouldBe (testBalance subtract BigInteger.valueOf(10l))
    val utxos4 = pool.getUTXOs(testPublicKey, BigInteger.valueOf(100l))
    utxos4 shouldBe None

    val utxos5 = pool.getUTXOs(ED25519PublicKey.zero, BigInteger.valueOf(10l))
    utxos5 shouldBe defined
    utxos5.get._2 shouldBe BigInteger.valueOf(10l)
    val utxos6 = pool.getUTXOs(ED25519PublicKey.zero, BigInteger.valueOf(11l))
    utxos6 shouldBe None
  }
}
