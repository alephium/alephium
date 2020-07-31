package org.alephium.flow.core

import scala.collection.mutable

import org.scalatest.Assertion

import org.alephium.flow.AlephiumFlowSpec
import org.alephium.flow.setting.ConsensusSetting
import org.alephium.io.IOResult
import org.alephium.protocol.Hash
import org.alephium.protocol.model.Block
import org.alephium.util.{AVector, TimeStamp}

class ChainDifficultyAdjustmentSpec extends AlephiumFlowSpec { Test =>
  case class HashState(parentOpt: Option[Hash], timestamp: TimeStamp, height: Int)

  trait Fixture extends ChainDifficultyAdjustment {
    override val consensusConfig: ConsensusSetting = Test.consensusConfig

    val hashesTable = mutable.HashMap.empty[Hash, HashState]
    hashesTable(Hash.zero) = HashState(None, TimeStamp.zero, 0)

    def getHeight(hash: Hash): IOResult[Int] =
      Right(hashesTable(hash).height)

    def getTimestamp(hash: Hash): IOResult[TimeStamp] =
      Right(hashesTable(hash).timestamp)

    def chainBack(hash: Hash, heightUntil: Int): IOResult[AVector[Hash]] = {
      val state = hashesTable(hash)
      if (state.height > heightUntil) chainBack(state.parentOpt.get, heightUntil).map(_ :+ hash)
      else Right(AVector.empty)
    }

    def calMedianBlockTime(hash: Hash): IOResult[(TimeStamp, TimeStamp)] = {
      calMedianBlockTime(hash, hashesTable(hash).height)
    }

    var currentHash   = Hash.zero
    var currentHeight = 0
    def addNewHash(n: Int): Unit = {
      val timestamp = TimeStamp.unsafe(n.toLong)
      val newHash   = Hash.random
      hashesTable(newHash) = HashState(Some(currentHash), timestamp, currentHeight + 1)
      currentHash = newHash
      currentHeight += 1
    }
  }

  it should "calculate target correctly" in new Fixture {
    val genesis       = Block.genesis(AVector.empty, consensusConfig.maxMiningTarget, 0)
    val gHeader       = genesis.header
    val currentTarget = genesis.header.target
    reTarget(currentTarget, consensusConfig.expectedTimeSpan.millis) is gHeader.target
    reTarget(currentTarget, (consensusConfig.expectedTimeSpan timesUnsafe 2).millis) is (gHeader.target * 2)
    reTarget(currentTarget, (consensusConfig.expectedTimeSpan divUnsafe 2).millis) is (gHeader.target / 2)
  }

  it should "compute the correct median value" in {
    import ChainDifficultyAdjustment.calMedian

    def checkCalMedian(tss: AVector[Long], expected1: Long, expected2: Long): Assertion = {
      val expected = (TimeStamp.unsafe(expected1), TimeStamp.unsafe(expected2))
      calMedian(tss.map(TimeStamp.unsafe)) is expected
    }

    checkCalMedian(AVector(0, 1, 2, 3, 4, 5, 6, 7), 4, 3)
    checkCalMedian(AVector(7, 6, 5, 4, 3, 2, 1, 0), 3, 4)
  }

  it should "calculate correct median block time" in new Fixture {
    assertThrows[AssertionError](calMedianBlockTime(currentHash))

    for (i <- 1 until consensusConfig.medianTimeInterval) {
      addNewHash(i)
    }
    assertThrows[AssertionError](calMedianBlockTime(currentHash))

    addNewHash(consensusConfig.medianTimeInterval)
    assertThrows[AssertionError](calMedianBlockTime(currentHash))

    addNewHash(consensusConfig.medianTimeInterval + 1)
    val median1 = TimeStamp.unsafe(((consensusConfig.medianTimeInterval + 3) / 2).toLong)
    val median2 = TimeStamp.unsafe(((consensusConfig.medianTimeInterval + 1) / 2).toLong)
    calMedianBlockTime(currentHash) isE (median1 -> median2)
  }

  it should "adjust difficulty properly" in new Fixture {
    for (i <- 1 until consensusConfig.medianTimeInterval) {
      addNewHash(i)
    }
    calHashTarget(currentHash, 9999) isE 9999

    addNewHash(consensusConfig.medianTimeInterval)
    calHashTarget(currentHash, 9999) isE 9999

    addNewHash(consensusConfig.medianTimeInterval + 1)
    val expected = BigInt(9999) * consensusConfig.timeSpanMin.millis / consensusConfig.expectedTimeSpan.millis
    calHashTarget(currentHash, 9999) isE expected
  }
}
