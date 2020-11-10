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

package org.alephium.protocol.model

import org.scalacheck.Gen

import org.alephium.protocol.{Hash, PublicKey}
import org.alephium.util.{AlephiumSpec, TimeStamp, U256}

class TransactionSpec extends AlephiumSpec with NoIndexModelGenerators {
  it should "generate distinct coinbase transactions" in {
    val (_, key) = GroupIndex.unsafe(0).generateKey
    val coinbaseTxs = (0 to 1000).map(_ =>
      Transaction
        .coinbase(ChainIndex.unsafe(0, 0), 0, key, Hash.generate.bytes, Target.Max, TimeStamp.zero))

    coinbaseTxs.size is coinbaseTxs.distinct.size
  }

  it should "calculate chain index" in {
    forAll(chainIndexGen) { chainIndex =>
      forAll(transactionGen(chainIndexGen = Gen.const(chainIndex))) { tx =>
        tx.chainIndex is chainIndex
      }
    }
  }

  it should "avoid hash collision for coinbase txs" in {
    val publicKey = PublicKey.generate
    val coinbase0 = Transaction.coinbase(ChainIndex.unsafe(0, 0),
                                         gasFee = U256.Zero,
                                         publicKey,
                                         target  = Target.Max,
                                         blockTs = TimeStamp.zero)
    val coinbase1 = Transaction.coinbase(ChainIndex.unsafe(0, 1),
                                         gasFee = U256.Zero,
                                         publicKey,
                                         target  = Target.Max,
                                         blockTs = TimeStamp.zero)
    val coinbase2 = Transaction.coinbase(ChainIndex.unsafe(0, 0),
                                         gasFee = U256.Zero,
                                         publicKey,
                                         target  = Target.Max,
                                         blockTs = TimeStamp.now())
    (coinbase0.hash equals coinbase1.hash) is false
    (coinbase0.hash equals coinbase2.hash) is false
  }
}
