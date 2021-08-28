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

import akka.util.ByteString
import org.scalacheck.Gen

import org.alephium.crypto.{Blake2b, Blake3, MerkleHashable}
import org.alephium.protocol._
import org.alephium.protocol.SignatureSchema
import org.alephium.protocol.config.NetworkConfigFixture
import org.alephium.protocol.vm.{GasPrice, LockupScript, StatefulScript}
import org.alephium.protocol.vm.{GasBox, UnlockScript}
import org.alephium.serde._
import org.alephium.util.{AlephiumSpec, AVector, Hex, TimeStamp, U256}

class BlockSpec extends AlephiumSpec with NoIndexModelGenerators with NetworkConfigFixture.Default {
  it should "serde" in {
    forAll(blockGen) { block =>
      val bytes  = serialize[Block](block)
      val output = deserialize[Block](bytes).toOption.get
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
              GasPrice(U256.unsafe(Random.nextLong(Long.MaxValue))),
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

  it should "put non-script txs in the last" in {
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
          GasPrice(U256.unsafe(gasPrice0)),
          AVector.empty,
          AVector.empty
        ),
        AVector.empty[Signature]
      )
      val tx1 = Transaction.from(
        UnsignedTransaction(
          None,
          minimalGas,
          GasPrice(U256.unsafe(gasPrice1)),
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
        ALF.LaunchTimestamp
      )

      val block0 = Block(header, AVector(tx0, tx1, coinbase))
      Block.scriptIndexes(block0.nonCoinbase).toSeq is Seq(0)
      block0.getNonCoinbaseExecutionOrder.last is 1
      val block1 = Block(header, AVector(tx1, tx0, coinbase))
      Block.scriptIndexes(block1.nonCoinbase).toSeq is Seq(1)
      block1.getNonCoinbaseExecutionOrder.last is 0
    }
  }

  it should "seder the snapshots properly" in new ModelSnapshotsHelper {
    implicit val basePath = "src/test/resources/models/block"

    import Hex._

    {
      info("empty block")

      val header = BlockHeader(
        version = defaultBlockVersion,
        blockDeps = BlockDeps.unsafe(AVector.fill(groupConfig.depsNum)(BlockHash.zero)),
        depStateHash =
          Hash.unsafe(hex"e5d64f886664c58378d41fe3b8c29dd7975da59245a4a6bf92c3a47339a9a0a9"),
        txsHash =
          Hash.unsafe(hex"c78682d23662320d6f59d6612f26e2bcb08caff68b589523064924328f6d0d59"),
        timestamp = TimeStamp.unsafe(1),
        target = consensusConfig.maxMiningTarget,
        nonce = Nonce.zero
      )

      val block = Block(header, AVector.empty)

      block.verify("empty-block")
    }

    {
      info("with transactions")

      val tokenId1 = Hash(hex"342f94b2e48e687a3f985ac55658bcdddace8891919fc08d58b0db2255ca3822")
      val tokenId2 = Hash(hex"2d257dfb825bd2c4ee87c9ebf45d6fafc1b628d3f01a85a877ca00c017fca056")

      val tx1 = {
        val unsignedTx = UnsignedTransaction(
          NetworkId(2),
          scriptOpt = None,
          GasBox.unsafe(100000),
          GasPrice(U256.unsafe(1000000000)),
          AVector(
            TxInput(
              AssetOutputRef.unsafe(
                Hint.unsafe(-1038667625),
                Hash(hex"a5ecc0fa7bce6fd6a868621a167b3aad9a4e2711353aef60196062509b8c3dc7")
              ),
              p2pkh(
                PublicKey(hex"03d7b2d064a1cf0f55266314dfcd50926ba032069b5c3dda7fd7c83c3ea8055249")
              )
            )
          ),
          AVector(
            AssetOutput(
              U256.unsafe(1000),
              p2pkh(Hash(hex"c03ce271334db24f37313bbbf2d4aced9c6223d1378b1f472ec56f0b30aaac04")),
              TimeStamp.unsafe(0),
              AVector((tokenId1, U256.unsafe(10)), (tokenId2, U256.unsafe(20))),
              hex"55deff667f0096ffc024ff53d6017ff5"
            ),
            AssetOutput(
              U256.unsafe(200),
              p2sh(hex"2ac11ec0a41ac91a309da23092fdca9c407f99a05a2c66c179f10d51050b8dfe"),
              TimeStamp.unsafe(0),
              AVector((tokenId1, U256.unsafe(15)), (tokenId2, U256.unsafe(25))),
              hex"7fa5c3fd66ff751c"
            )
          )
        )

        val signature = SignatureSchema.sign(
          unsignedTx.hash.bytes,
          PrivateKey(hex"d803bda2a7b5e2110d1302fe6f9fef18d6b4c38bc4f5e1c31b5830dfb73be216")
        )

        Transaction(
          unsignedTx,
          contractInputs = AVector.empty,
          generatedOutputs = AVector.empty,
          inputSignatures = AVector(signature),
          contractSignatures = AVector.empty
        )
      }

      val tx2 = {
        val unsignedTx = UnsignedTransaction(
          NetworkId(2),
          None,
          GasBox.unsafe(100000),
          GasPrice(U256.unsafe(1000000000)),
          AVector(
            TxInput(
              AssetOutputRef.unsafe(
                Hint.unsafe(-1038667625),
                Hash(hex"a5ecc0fa7bce6fd6a868621a167b3aad9a4e2711353aef60196062509b8c3dc7")
              ),
              p2pkh(
                PublicKey(hex"0298d66776af8012ca087214c10f242db3d220f1181ca0cc9f4f6172371f8fae15")
              )
            )
          ),
          AVector(
            AssetOutput(
              100,
              p2pkh(Hash(hex"d2d3f28a281a7029fa8442f2e5d7a9962c9ad8680bba8fa9df62fbfbe01f6efd")),
              TimeStamp.unsafe(1630168595025L),
              AVector.empty,
              hex"00010000017b8d959291"
            )
          )
        )

        val signature = SignatureSchema.sign(
          unsignedTx.hash.bytes,
          PrivateKey(hex"227cd87dfdbc7e82073d3e05a511ee5c3af2bbd716a498c44e84c098b82be986")
        )

        Transaction(
          unsignedTx,
          contractInputs = AVector.empty,
          generatedOutputs = AVector.empty,
          inputSignatures = AVector(signature),
          contractSignatures = AVector.empty
        )
      }

      val coinbaseTx = Transaction.coinbase(
        ChainIndex.unsafe(0),
        AVector(tx1, tx2),
        p2pkh(Hash(hex"0478042acbc0e37b410e5d2c7aebe367d47f39aa78a65277b7f8bb7ce3c5e036")),
        consensusConfig.maxMiningTarget,
        TimeStamp.unsafe(1629980707001L)
      )

      val allTxs  = AVector(tx1, tx2, coinbaseTx)
      val txsHash = Block.calTxsHash(allTxs)

      val header = BlockHeader(
        nonce = Nonce.unsafe(hex"bb557f744763ca4f5ef8079b4b76c2dbb26a4cd845fbc84d"),
        version = defaultBlockVersion,
        blockDeps = BlockDeps.build(
          deps = AVector(
            new Blake3(hex"f4e21b0811b4d1a56d016d4980cdcb34708de0d96050e077ac6a28bc3831be97"),
            new Blake3(hex"abb46756a535f6912c90f9f06f503eed53748697f4fad672da1557e2126fa760"),
            new Blake3(hex"aecea2ddb52f00109726408bb1eb86bbde953fe696c57e6517c93b27973cc805"),
            new Blake3(hex"6725874ac2a55cd70b1ffec51b2afb46eeaf098052e5352582f2ff0135da127e"),
            new Blake3(hex"4325ecfd044d88e58c3537275381d1c3a1f410812a2847382058e5686dccfd7a")
          )
        ),
        depStateHash = Hash(hex"a670c675a926606f1f01fe28660c50621fe31719414f43eccfa871432fe8ce8a"),
        txsHash = txsHash,
        timestamp = TimeStamp.unsafe(1630167995025L),
        target = Target(hex"20ffffff")
      )

      val block = Block(header, allTxs)
      block.verify("with-transactions")
    }
  }

  def p2sh(bs: ByteString) = {
    LockupScript.P2SH(new Hash(bs))
  }

  def p2pkh(keyHash: Hash) = {
    LockupScript.P2PKH(keyHash)
  }

  def p2pkh(key: PublicKey) = {
    UnlockScript.P2PKH(key)
  }
}
