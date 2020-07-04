package org.alephium.flow.core

import scala.language.reflectiveCalls

import org.scalatest.Assertion

import org.alephium.flow.AlephiumFlowSpec
import org.alephium.flow.io.ChainStateStorage
import org.alephium.protocol.ALF.Hash
import org.alephium.protocol.config.ConsensusConfig
import org.alephium.protocol.io.IOResult
import org.alephium.protocol.model.ChainIndex
import org.alephium.util.{ConcurrentHashMap, TimeStamp}

class BlockHashChainStateSpec extends AlephiumFlowSpec { Test =>
  trait Fixture {
    val chainState = new BlockHashChainState {
      private val dummyIndex               = ChainIndex.unsafe(0, 0)(config)
      override def config: ConsensusConfig = Test.config
      override def chainStateStorage: ChainStateStorage =
        Test.storages.nodeStateStorage.chainStateStorage(dummyIndex)

      override def getTimestamp(hash: Hash): IOResult[TimeStamp] = ???

      def state: BlockHashChain.State = chainStateStorage.loadState().toOption.get

      def allTipsInMem: ConcurrentHashMap[Hash, TimeStamp] = tips

      chainStateStorage.clearState().isRight is true
    }

    val hashes = Seq.fill(10)(Hash.random)

    def checkState(numHashes: Int, expected: Set[Hash]): Assertion = {
      chainState.numHashes is numHashes
      chainState.allTipsInMem.keys.toSet is expected
      chainState.state.tips.toSet is expected
    }
  }

  it should "add tips properly" in new Fixture {
    chainState.setGenesisState(hashes(0), TimeStamp.zero)
    checkState(1, Set(hashes(0)))
    chainState.updateState(hashes(1), TimeStamp.zero, hashes(0))
    checkState(2, Set(hashes(1)))
    chainState.updateState(hashes(3), TimeStamp.zero, hashes(2))
    checkState(3, Set(hashes(1), hashes(3)))
    chainState.updateState(hashes(4), TimeStamp.zero, hashes(1))
    checkState(4, Set(hashes(4), hashes(3)))
  }

  it should "prune properly" in new Fixture {
    chainState.setGenesisState(hashes(0), TimeStamp.zero)
    chainState.setGenesisState(hashes(1), TimeStamp.unsafe(1))
    checkState(2, Set(hashes(0), hashes(1)))
    chainState.setGenesisState(hashes(2), TimeStamp.unsafe(1) + Test.config.tipsPruneDuration)
    checkState(3, Set(hashes(1), hashes(2)))
  }
}
