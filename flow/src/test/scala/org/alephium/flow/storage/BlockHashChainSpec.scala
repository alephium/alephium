package org.alephium.flow.storage

import org.alephium.crypto.Keccak256
import org.alephium.flow.PlatformConfig
import org.alephium.protocol.model.Block
import org.alephium.util.{AVector, AlephiumSpec}

class BlockHashChainSpec extends AlephiumSpec with PlatformConfig.Default { Self =>
  trait Fixture extends BlockHashChain {
    val root   = BlockHashChain.Root(Keccak256.zero, 0, 0, 0)
    val config = Self.config

    var currentNode: BlockHashChain.TreeNode = root
    def addNewHash(n: Int): Unit = {
      val timestamp = n.toLong
      val newHash   = Keccak256.random
      addHash(newHash, currentNode, 0, timestamp)
      currentNode = getNode(newHash)
    }
  }

  it should "calculate target correctly" in new Fixture {
    val genesis       = Block.genesis(AVector.empty, config.maxMiningTarget, 0)
    val gHeader       = genesis.header
    val currentTarget = genesis.header.target
    reTarget(currentTarget, config.expectedTimeSpan) is gHeader.target
    reTarget(currentTarget, 2 * config.expectedTimeSpan) is (2 * gHeader.target)
    reTarget(currentTarget, config.expectedTimeSpan / 2) is (gHeader.target / 2)
  }

  it should "compute the correct median value" in new Fixture {
    calMedian(Array(0, 1, 2, 3, 4, 5, 6)) is 3
    calMedian(Array(1, 2, 3, 4, 5, 6, 7)) is 4
    calMedian(Array(6, 5, 4, 3, 2, 1, 0)) is 3
    calMedian(Array(7, 6, 5, 4, 3, 2, 1)) is 4
  }

  it should "calculate correct median block time" in new Fixture {
    calMedianBlockTime(currentNode) is None

    for (i <- 1 until config.medianTimeInterval) {
      addNewHash(i)
    }
    calMedianBlockTime(currentNode) is None

    addNewHash(config.medianTimeInterval)
    calMedianBlockTime(currentNode).get is ((config.medianTimeInterval + 1) / 2).toLong

    addNewHash(config.medianTimeInterval + 1)
    calMedianBlockTime(currentNode).get is ((config.medianTimeInterval + 3) / 2).toLong
  }

  it should "adjust difficulty properly" in new Fixture {
    for (i <- 1 until config.medianTimeInterval) {
      addNewHash(i)
    }
    calHashTarget(currentNode.blockHash, 9999) is 9999

    addNewHash(config.medianTimeInterval)
    calHashTarget(currentNode.blockHash, 9999) is 9999

    addNewHash(config.medianTimeInterval + 1)
    val expected = (BigInt(9999) * config.timeSpanMin / config.expectedTimeSpan)
    calHashTarget(currentNode.blockHash, 9999) is expected
  }
}
