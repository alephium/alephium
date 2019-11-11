package org.alephium.flow.core

import org.scalatest.Assertion

import org.alephium.crypto.Keccak256
import org.alephium.flow.AlephiumFlowSpec
import org.alephium.protocol.model.Block
import org.alephium.util.{AVector, TimeStamp}

class BlockHashChainSpec extends AlephiumFlowSpec { Self =>
  trait Fixture extends BlockHashChain {
    val root   = BlockHashChain.Root(Keccak256.zero, 0, 0, TimeStamp.zero)
    val config = Self.config

    var currentNode: BlockHashChain.TreeNode = root
    def addNewHash(n: Int): Unit = {
      val timestamp = TimeStamp.fromMillis(n.toLong)
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
    reTarget(currentTarget, config.expectedTimeSpan * 2) is (gHeader.target * 2)
    reTarget(currentTarget, config.expectedTimeSpan / 2) is (gHeader.target / 2)
  }

  it should "compute the correct median value" in new Fixture {
    def checkCalMedian(tss: Array[Long], expected: Long): Assertion = {
      calMedian(tss.map(TimeStamp.fromMillis)) is TimeStamp.fromMillis(expected)
    }

    checkCalMedian(Array(0, 1, 2, 3, 4, 5, 6), 3)
    checkCalMedian(Array(1, 2, 3, 4, 5, 6, 7), 4)
    checkCalMedian(Array(6, 5, 4, 3, 2, 1, 0), 3)
    checkCalMedian(Array(7, 6, 5, 4, 3, 2, 1), 4)
  }

  it should "calculate correct median block time" in new Fixture {
    calMedianBlockTime(currentNode) is None

    for (i <- 1 until config.medianTimeInterval) {
      addNewHash(i)
    }
    calMedianBlockTime(currentNode) is None

    addNewHash(config.medianTimeInterval)
    calMedianBlockTime(currentNode).get is TimeStamp.fromMillis(
      ((config.medianTimeInterval + 1) / 2).toLong)

    addNewHash(config.medianTimeInterval + 1)
    calMedianBlockTime(currentNode).get is TimeStamp.fromMillis(
      ((config.medianTimeInterval + 3) / 2).toLong)
  }

  it should "adjust difficulty properly" in new Fixture {
    for (i <- 1 until config.medianTimeInterval) {
      addNewHash(i)
    }
    calHashTarget(currentNode.blockHash, 9999) is 9999

    addNewHash(config.medianTimeInterval)
    calHashTarget(currentNode.blockHash, 9999) is 9999

    addNewHash(config.medianTimeInterval + 1)
    val expected = BigInt(9999) * config.timeSpanMin.millis / config.expectedTimeSpan.millis
    calHashTarget(currentNode.blockHash, 9999) is expected
  }
}
