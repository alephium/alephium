// Copyright 2018 The Alephium Authors
// This file is part of the alephium project.
//
// The library is free software: you can redistribute it and/or modify
// it under the terms of the GNU Lesser General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// The library is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
// GNU Lesser General Public License for more details.
//
// You should have received a copy of the GNU Lesser General Public License
// along with the library. If not, see <http://www.gnu.org/licenses/>.

package org.alephium.flow.core

import scala.language.reflectiveCalls

import org.scalatest.Assertion

import org.alephium.flow.AlephiumFlowSpec
import org.alephium.flow.io.ChainStateStorage
import org.alephium.io.IOResult
import org.alephium.protocol.model.{BlockHash, ChainIndex}
import org.alephium.util.{ConcurrentHashMap, TimeStamp}

class BlockHashChainStateSpec extends AlephiumFlowSpec { Test =>
  trait Fixture {
    val chainState = new BlockHashChainState {
      private val dummyIndex       = ChainIndex.unsafe(0, 0)
      override def consensusConfig = Test.consensusConfig
      override def chainStateStorage: ChainStateStorage =
        Test.storages.nodeStateStorage.chainStateStorage(dummyIndex)

      override def getTimestamp(hash: BlockHash): IOResult[TimeStamp] = ???
      override def getTimestampUnsafe(hash: BlockHash): TimeStamp     = ???

      def state: BlockHashChain.State = chainStateStorage.loadState().rightValue

      def allTipsInMem: ConcurrentHashMap[BlockHash, TimeStamp] = tips

      chainStateStorage.clearState().isRight is true
    }

    val hashes = Seq.fill(10)(BlockHash.random)

    def checkState(numHashes: Int, expected: Set[BlockHash]): Assertion = {
      chainState.numHashes is numHashes
      chainState.allTipsInMem.keys().toSet is expected
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
    chainState.setGenesisState(
      hashes(2),
      TimeStamp.unsafe(1) + Test.consensusConfig.tipsPruneDuration
    )
    checkState(3, Set(hashes(1), hashes(2)))
  }
}
