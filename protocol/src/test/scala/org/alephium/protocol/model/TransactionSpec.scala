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

import org.alephium.protocol._
import org.alephium.protocol.config.NetworkConfigFixture
import org.alephium.protocol.vm.{GasBox, GasPrice, LockupScript}
import org.alephium.serde._
import org.alephium.util.{AlephiumSpec, AVector, Hex, TimeStamp, U256}

class TransactionSpec
    extends AlephiumSpec
    with NoIndexModelGenerators
    with NetworkConfigFixture.Default {

  it should "serde successfully" in {
    info("transation")
    forAll(transactionGen()) { transaction =>
      val bytes  = serialize[Transaction](transaction)
      val output = deserialize[Transaction](bytes).toOption.value
      output is transaction
    }

    info("merkle transation")
    forAll(transactionGen()) { transaction =>
      val merkleTx = transaction.toMerkleTx
      val bytes    = serialize[Transaction.MerkelTx](merkleTx)
      val output   = deserialize[Transaction.MerkelTx](bytes).toOption.value
      output is merkleTx
    }
  }

  it should "generate distinct coinbase transactions" in {
    val (_, key) = GroupIndex.unsafe(0).generateKey
    val script   = LockupScript.p2pkh(key)
    val coinbaseTxs = (0 to 1000).map(_ =>
      Transaction
        .coinbase(
          ChainIndex.unsafe(0, 0),
          0,
          script,
          Hash.generate.bytes,
          Target.Max,
          ALF.LaunchTimestamp
        )
    )

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
    val script = LockupScript.p2pkh(PublicKey.generate)
    val coinbase0 = Transaction.coinbase(
      ChainIndex.unsafe(0, 0),
      gasFee = U256.Zero,
      script,
      target = Target.Max,
      blockTs = ALF.LaunchTimestamp
    )
    val coinbase1 = Transaction.coinbase(
      ChainIndex.unsafe(0, 1),
      gasFee = U256.Zero,
      script,
      target = Target.Max,
      blockTs = ALF.LaunchTimestamp
    )
    val coinbase2 = Transaction.coinbase(
      ChainIndex.unsafe(0, 0),
      gasFee = U256.Zero,
      script,
      target = Target.Max,
      blockTs = TimeStamp.now()
    )
    (coinbase0.id equals coinbase1.id) is false
    (coinbase0.id equals coinbase2.id) is false
  }

  it should "calculate the merkle hash" in {
    forAll(transactionGen()) { tx =>
      val txSerialized       = serialize(tx)
      val merkelTxSerialized = serialize(tx.toMerkleTx)
      val idSerialized       = serialize(tx.id)
      val unsignedSerialized = serialize(tx.unsigned)
      txSerialized.startsWith(unsignedSerialized)
      merkelTxSerialized.startsWith(idSerialized)
      txSerialized.drop(unsignedSerialized.length) is merkelTxSerialized.drop(idSerialized.length)

      tx.merkleHash is Hash.hash(merkelTxSerialized)
    }
  }


  it should "cap the gas reward" in {
    val hardReward = ALF.oneAlf
    Transaction.totalReward(1, 100) is U256.unsafe(100)
    Transaction.totalReward(2, 100) is U256.unsafe(101)
    Transaction.totalReward(200, 100) is U256.unsafe(200)
    Transaction.totalReward(202, 100) is U256.unsafe(201)
    Transaction.totalReward(hardReward * 2, hardReward) is (hardReward * 2)
    Transaction.totalReward(hardReward * 2 + 2, hardReward) is (hardReward * 2)
    Transaction.totalReward(hardReward * 2, 0) is hardReward
    Transaction.totalReward(hardReward * 2 + 2, 0) is hardReward
  }

  it should "seder the snapshots properly" in new TransactionSnapshotsFixture {
    implicit val basePath = "src/test/resources/models/transaction"

    import Hex._

    val privKey1 =
      PrivateKey.unsafe(hex"d803bda2a7b5e2110d1302fe6f9fef18d6b4c38bc4f5e1c31b5830dfb73be216")

    {
      info("no inputs and outputs")

      val unsignedTx = UnsignedTransaction(
        networkId,
        scriptOpt = None,
        GasBox.unsafe(100000),
        GasPrice(1000000000),
        AVector.empty,
        AVector.empty
      )

      val tx = inputSign(unsignedTx, privKey1)
      tx.verify("no-input-and-output")
    }

    {
      info("coinbase transaction")

      val tx = coinbaseTransaction()
      tx.verify("coinbase")
    }
  }
}
