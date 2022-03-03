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

import scala.util.Random

import org.scalacheck.Gen

import org.alephium.crypto.{Blake2b, Blake3, MerkleHashable}
import org.alephium.protocol._
import org.alephium.protocol.vm.{GasPrice, LockupScript, StatefulScript}
import org.alephium.serde._
import org.alephium.util.{AlephiumSpec, AVector, Hex, TimeStamp, U256}

class BlockSpec extends AlephiumSpec with NoIndexModelGenerators {
  it should "serde" in {
    forAll(blockGen) { block =>
      val bytes  = serialize[Block](block)
      val output = deserialize[Block](bytes).rightValue
      output is block
    }
  }

  it should "hash" in {
    forAll(blockGen) { block =>
      val expected = Blake3.hash(Blake3.hash(serialize(block.header)).bytes)
      block.hash is block.header.hash
      block.hash is expected
    }
  }

  it should "hash transactions" in {
    forAll(blockGen) { block =>
      val expected = MerkleHashable.rootHash(Blake2b, block.transactions)
      block.header.txsHash is expected
    }
  }

  it should "calculate chain index" in {
    forAll(chainIndexGen) { chainIndex =>
      val block = blockGen(chainIndex).sample.get
      block.chainIndex is chainIndex
    }
  }

  it should "calculate proper execution order" in {
    forAll(blockGen) { block =>
      val order = block.getNonCoinbaseExecutionOrder
      order.length is block.transactions.length - 1
      order.sorted is AVector.tabulate(block.transactions.length - 1)(identity)
    }
  }

  it should "randomize the script execution order" in {
    def gen(): Block = {
      val header: BlockHeader =
        BlockHeader.unsafeWithRawDeps(
          AVector.fill(groupConfig.depsNum)(BlockHash.zero),
          Hash.zero,
          Hash.zero,
          TimeStamp.now(),
          Target.Max,
          Nonce.zero
        )
      val txs: AVector[Transaction] =
        AVector.fill(3)(
          Transaction.from(
            UnsignedTransaction(
              Some(StatefulScript.unsafe(AVector.empty)),
              minimalGas,
              GasPrice(Random.nextLong(Long.MaxValue)),
              AVector.empty,
              AVector.empty
            ),
            AVector.empty[Signature]
          )
        )
      Block(header, txs :+ txs.head) // add a fake coinbase tx
    }
    val blockGen = Gen.const(()).map(_ => gen())

    Seq(0, 1, 2).permutations.foreach { orders =>
      val blockGen0 = blockGen.retryUntil(_.getScriptExecutionOrder equals AVector.from(orders))
      blockGen0.sample.nonEmpty is true
    }
  }

  it should "put non-script txs at the last" in {
    forAll(posLongGen, posLongGen) { (gasPrice0: Long, gasPrice1: Long) =>
      val header: BlockHeader =
        BlockHeader.unsafeWithRawDeps(
          AVector.fill(groupConfig.depsNum)(BlockHash.zero),
          Hash.zero,
          Hash.zero,
          TimeStamp.now(),
          Target.Max,
          Nonce.zero
        )
      val tx0 = Transaction.from(
        UnsignedTransaction(
          Some(StatefulScript.unsafe(AVector.empty)),
          minimalGas,
          GasPrice(gasPrice0),
          AVector.empty,
          AVector.empty
        ),
        AVector.empty[Signature]
      )
      val tx1 = Transaction.from(
        UnsignedTransaction(
          None,
          minimalGas,
          GasPrice(gasPrice1),
          AVector.empty,
          AVector.empty
        ),
        AVector.empty[Signature]
      )
      val coinbase = Transaction.coinbase(
        ChainIndex.unsafe(0, 0),
        U256.Zero,
        LockupScript.p2pkh(PublicKey.generate),
        Target.Max,
        ALPH.LaunchTimestamp
      )

      val block0 = Block(header, AVector(tx0, tx1, coinbase))
      Block.scriptIndexes(block0.nonCoinbase).toSeq is Seq(0)
      block0.getNonCoinbaseExecutionOrder.last is 1
      val block1 = Block(header, AVector(tx1, tx0, coinbase))
      Block.scriptIndexes(block1.nonCoinbase).toSeq is Seq(1)
      block1.getNonCoinbaseExecutionOrder.last is 0
    }
  }

  it should "seder the snapshots properly" in new BlockSnapshotsFixture {
    implicit val basePath = "src/test/resources/models/block"

    import Hex._

    {
      info("empty block")

      block().verify("empty-block")
    }

    {
      info("with transactions that has p2sh and p2pkh outputs")

      val transaction1 = {
        val unsignedTx = unsignedTransaction(
          pubKey1,
          scriptOpt = None,
          p2pkhOutput(
            1000,
            hex"c03ce271334db24f37313bbbf2d4aced9c6223d1378b1f472ec56f0b30aaac04",
            additionalData = hex"55deff667f0096ffc024ff53d6017ff5"
          ),
          p2shOutput(
            200,
            hex"2ac11ec0a41ac91a309da23092fdca9c407f99a05a2c66c179f10d51050b8dfe",
            additionalData = hex"7fa5c3fd66ff751c"
          )
        )

        inputSign(unsignedTx, privKey1)
      }

      val transaction2 = {
        val unsignedTx = unsignedTransaction(
          pubKey2,
          scriptOpt = None,
          p2pkhOutput(
            100,
            hex"d2d3f28a281a7029fa8442f2e5d7a9962c9ad8680bba8fa9df62fbfbe01f6efd",
            TimeStamp.unsafe(1630168595025L),
            additionalData = hex"00010000017b8d959291"
          )
        )

        inputSign(unsignedTx, privKey2)
      }

      val blk = block(transaction1, transaction2)
      blk.verify("txs-with-p2pkh-p2sh-outputs")
    }

    {
      info("with token transfer")

      val tokens = {
        val tokenId1 =
          Hash.unsafe(hex"342f94b2e48e687a3f985ac55658bcdddace8891919fc08d58b0db2255ca3822")
        val tokenId2 =
          Hash.unsafe(hex"2d257dfb825bd2c4ee87c9ebf45d6fafc1b628d3f01a85a877ca00c017fca056")

        AVector((tokenId1, U256.unsafe(10)), (tokenId2, U256.unsafe(20)))
      }

      val transaction = {
        val unsignedTx = unsignedTransaction(
          pubKey1,
          scriptOpt = None,
          p2pkhOutput(
            1000,
            hex"b03ce271334db24f37313cccf2d4aced9c6223d1378b1f472ec56f0b30aaac0f",
            TimeStamp.unsafe(12345),
            additionalData = hex"15deff667f0096ffc024ff53d6017ff5",
            tokens
          )
        )

        inputSign(unsignedTx, privKey1)
      }

      val blk = block(transaction)
      blk.verify("txs-with-tokens")
    }

    {
      info("with optional script")

      val script =
        s"""
         |TxScript Foo {
         |  pub fn add() -> () {
         |  }
         |}
         |""".stripMargin

      val transaction = {
        val unsignedTx = unsignedTransaction(
          pubKey1,
          Some(script),
          p2pkhOutput(
            1000,
            hex"b03ce271334db24f37313cccf2d4aced9c6223d1378b1f472ec56f0b30aaac0f",
            TimeStamp.unsafe(12345),
            additionalData = hex"15deff667f0096ffc024ff53d6017ff5"
          )
        )

        inputSign(unsignedTx, privKey1)
      }

      val blk = block(transaction)
      blk.verify("txs-with-script")
    }

    {
      info("with contract inputs and outputs")

      // Pay to pubKey2
      val address = Address.p2pkh(pubKey2).toBase58
      def script(address: String) =
        s"""
         |TxScript Main {
         | pub payable fn main() -> () {
         |   verifyTxSignature!(#${pubKey2.toHexString})
         |   transferAlphFromSelf!(@$address, 5)
         | }
         |}
         |""".stripMargin

      val transaction = {
        val unsignedTx = unsignedTransaction(
          pubKey1,
          Some(script(address)),
          p2pkhOutput(
            1000,
            hex"b03ce271334db24f37313cccf2d4aced9c6223d1378b1f472ec56f0b30aaac0f",
            TimeStamp.unsafe(12345),
            additionalData = hex"15deff667f0096ffc024ff53d6017ff5"
          )
        )

        val transaction = inputSign(unsignedTx, privKey1)

        val updatedTransaction = transaction.copy(
          contractInputs = AVector(
            ContractOutputRef.unsafe(
              Hint.unsafe(-1038667620),
              Hash.unsafe(hex"1334b03ce27313db24ace4fb1f72ec56f0bc6223d137430aaac0f37cccf2dd98")
            )
          ),
          generatedOutputs = AVector(
            AssetOutput(
              5,
              LockupScript.p2pkh(pubKey2),
              TimeStamp.unsafe(12345),
              tokens = AVector.empty,
              hex"ff66157f00de17ff596ffc53d024ff60"
            )
          )
        )

        contractSign(updatedTransaction, privKey2)
      }

      val blk = block(transaction)
      blk.verify("txs-with-contract-inputs-outputs")
    }
  }
}
