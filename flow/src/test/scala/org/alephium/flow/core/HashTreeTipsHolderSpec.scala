package org.alephium.flow.core

import scala.language.reflectiveCalls

import org.scalatest.Assertion
import org.scalatest.EitherValues._

import org.alephium.flow.AlephiumFlowSpec
import org.alephium.flow.io.HashTreeTipsDB
import org.alephium.protocol.ALF.Hash
import org.alephium.protocol.config.ConsensusConfig
import org.alephium.protocol.model.ChainIndex
import org.alephium.util.{AVector, ConcurrentHashMap, TimeStamp}

class HashTreeTipsHolderSpec extends AlephiumFlowSpec { Test =>
  trait Fixture {
    val tipsHolder = new HashTreeTipsHolder {
      private val dummyIndex               = ChainIndex.unsafe(0, 0)(config)
      override def config: ConsensusConfig = Test.config
      override def tipsDB: HashTreeTipsDB =
        Test.config.storages.nodeStateStorage.hashTreeTipsDB(dummyIndex)

      def allTipsInMem: ConcurrentHashMap[Hash, TimeStamp] = tips

      def allTipsInDB: AVector[Hash] = tipsDB.loadTips().right.value

      tipsDB.clearTips().isRight is true
    }

    val hashes = Seq.fill(10)(Hash.random)

    def checkTips(expected: Set[Hash]): Assertion = {
      tipsHolder.allTipsInMem.keys.toSet is expected
      tipsHolder.allTipsInDB.toSet is expected
    }
  }

  it should "add tips properly" in new Fixture {
    tipsHolder.addGenesisTip(hashes(0), TimeStamp.zero)
    checkTips(Set(hashes(0)))
    tipsHolder.addNewTip(hashes(1), TimeStamp.zero, hashes(0))
    checkTips(Set(hashes(1)))
    tipsHolder.addNewTip(hashes(3), TimeStamp.zero, hashes(2))
    checkTips(Set(hashes(1), hashes(3)))
    tipsHolder.addNewTip(hashes(4), TimeStamp.zero, hashes(1))
    checkTips(Set(hashes(4), hashes(3)))
  }

  it should "prune properly" in new Fixture {
    tipsHolder.addGenesisTip(hashes(0), TimeStamp.zero)
    tipsHolder.addGenesisTip(hashes(1), TimeStamp.unsafe(1))
    checkTips(Set(hashes(0), hashes(1)))
    tipsHolder.addGenesisTip(hashes(2), TimeStamp.unsafe(1) + Test.config.blockConfirmTS)
    checkTips(Set(hashes(1), hashes(2)))
  }
}
