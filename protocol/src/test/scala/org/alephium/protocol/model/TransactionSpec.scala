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

import akka.util.ByteString
import org.scalacheck.Gen

import org.alephium.protocol._
import org.alephium.protocol.config.NetworkConfigFixture
import org.alephium.protocol.vm._
import org.alephium.protocol.vm.lang.Compiler
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
          ALPH.LaunchTimestamp
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
      blockTs = ALPH.LaunchTimestamp
    )
    val coinbase1 = Transaction.coinbase(
      ChainIndex.unsafe(0, 1),
      gasFee = U256.Zero,
      script,
      target = Target.Max,
      blockTs = ALPH.LaunchTimestamp
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
    val hardReward = ALPH.oneAlph
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

    {
      info("no inputs and outputs")

      val unsignedTx = UnsignedTransaction(
        DefaultTxVersion,
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

    {
      info("multiple fixed inputs and outputs")

      val unsignedTx = UnsignedTransaction(
        DefaultTxVersion,
        networkId,
        scriptOpt = None,
        GasBox.unsafe(100000),
        GasPrice(U256.unsafe(1000000000)),
        inputs = AVector(
          TxInput(
            AssetOutputRef.unsafe(
              Hint.unsafe(-1038667625),
              Hash.unsafe(hex"a5ecc0fa7bce6fd6a868621a167b3aad9a4e2711353aef60196062509b8c3dc7")
            ),
            UnlockScript.P2PKH(pubKey1)
          ),
          TxInput(
            AssetOutputRef.unsafe(
              Hint.unsafe(12347),
              Hash.unsafe(hex"0fa5fd6aecca7b21a167b3aad9a4e27762509b8c3ce68611353aef60196086dc")
            ),
            UnlockScript.P2PKH(pubKey2)
          ),
          TxInput(
            AssetOutputRef.unsafe(
              Hint.unsafe(-1038667625),
              Hash.unsafe(hex"ce6fd6a868621a167b62509b8c3dc7a5ecc0fa7b3aad9a4e2711353aef601960")
            ),
            UnlockScript.P2PKH(pubKey1)
          )
        ),
        fixedOutputs = AVector(
          p2shOutput(
            200,
            hex"2ac11ec0a41ac91a309da23092fdca9c407f99a05a2c66c179f10d51050b8dfe",
            additionalData = hex"7fa5c3fd66ff751c"
          ),
          p2pkhOutput(
            1000,
            hex"c03ce271334db24f37313bbbf2d4aced9c6223d1378b1f472ec56f0b30aaac04",
            additionalData = hex"55deff667f0096ffc024ff53d6017ff5"
          ),
          p2pkhOutput(
            100,
            hex"d2d3f28a281a7029fa8442f2e5d7a9962c9ad8680bba8fa9df62fbfbe01f6efd",
            TimeStamp.unsafe(1630168595025L),
            additionalData = hex"00010000017b8d959291"
          )
        )
      )

      val tx = inputSign(unsignedTx, privKey1, privKey2, privKey1)
      tx.verify("multiple-fixed-inputs-and-outputs")
    }

    {
      info("multiple contract inputs and generated outputs")

      val address1 = Address.p2pkh(pubKey1).toBase58
      val address2 = Address.p2pkh(pubKey2).toBase58
      val tokenId =
        Hash.unsafe(hex"342f94b2e48e687a3f985ac55658bcdddace8891919fc08d58b0db2255ca3822")

      val script =
        s"""
           |@use(approvedAssets = true, contractAssets = true)
           |TxScript Main {
           |  verifyTxSignature!(#${pubKey1.toHexString})
           |  transferAlphFromSelf!(@$address1, 1)
           |  transferTokenToSelf!(@$address1, #${tokenId.toHexString}, 42)
           |
           |  verifyTxSignature!(#${pubKey2.toHexString})
           |  transferAlphFromSelf!(@$address2, 5)
           |}
           |""".stripMargin

      val tx = {
        val unsignedTx = unsignedTransaction(
          pubKey1,
          Some(script),
          p2pkhOutput(
            55,
            hex"b03ce271334db24f37313cccf2d4aced9c6223d1378b1f472ec56f0b30aaac0f"
          ),
          p2shOutput(
            95,
            hex"f2d430aaac0fb03ce271334d1378b1f472ec56f0bb24f37313cccaced9c6223d"
          )
        )

        val transaction = inputSign(unsignedTx, privKey1)

        val updatedTransaction = transaction.copy(
          contractInputs = AVector(
            ContractOutputRef.unsafe(
              Hint.unsafe(-1038667620),
              Hash.unsafe(hex"1334b03ce27313db24ace4fb1f72ec56f0bc6223d137430aaac0f37cccf2dd98")
            ),
            ContractOutputRef.unsafe(
              Hint.unsafe(-1038667620),
              Hash.unsafe(hex"45ace4fb7430a1f72ec6f0bc622d981334b03ce27313db23d13aac0f37cccf2d")
            )
          ),
          generatedOutputs = AVector(
            AssetOutput(
              1,
              LockupScript.p2pkh(pubKey1),
              TimeStamp.unsafe(12345),
              tokens = AVector((tokenId, U256.unsafe(42))),
              ByteString.empty
            ),
            AssetOutput(
              5,
              LockupScript.p2pkh(pubKey2),
              TimeStamp.unsafe(12345),
              tokens = AVector.empty,
              ByteString.empty
            )
          )
        )

        contractSign(updatedTransaction, privKey1, privKey2)
      }

      tx.verify("multiple-contract-inputs-and-generated-outputs")
    }

    {
      info("transfer multiple tokens")
      val tokenId1 =
        Hash.unsafe(hex"342f94b2e48e687a3f985ac55658bcdddace8891919fc08d58b0db2255ca3822")
      val tokenId2 =
        Hash.unsafe(hex"2d257dfb825bd2c4ee87c9ebf45d6fafc1b628d3f01a85a877ca00c017fca056")
      val tokenId3 =
        Hash.unsafe(hex"825bd2b5d6fafc1b628d3f01c4ee87c9ebf4a85a877ca00c017fca0562d257df")

      val tx = {
        val unsignedTx = unsignedTransaction(
          pubKey1,
          scriptOpt = None,
          p2pkhOutput(
            55,
            hex"b03ce271334db24f37313cccf2d4aced9c6223d1378b1f472ec56f0b30aaac0f",
            TimeStamp.unsafe(12345),
            additionalData = hex"15deff667f0096ffc024ff53d6017ff5",
            tokens = AVector((tokenId1, U256.unsafe(10)), (tokenId2, U256.unsafe(20)))
          ),
          p2shOutput(
            95,
            hex"2ac11ec0a41ac91a309da23092fdca9c407f99a05a2c66c179f10d51050b8dfe",
            TimeStamp.unsafe(12345),
            additionalData = hex"7fa5c3fd66ff751c",
            tokens = AVector((tokenId1, U256.unsafe(500)), (tokenId3, U256.unsafe(211)))
          ),
          p2pkhOutput(
            42,
            hex"c03ce271334db24f37313bbbf2d4aced9c6223d1378b1f472ec56f0b30aaac04",
            TimeStamp.unsafe(12345),
            additionalData = hex"55deff667f0096ffc024ff53d6017ff5",
            tokens = AVector((tokenId1, U256.unsafe(2)))
          ),
          p2pkhOutput(
            100,
            hex"c6223d72ec56f13781334db24f37313fb03ce27cccf2d4aced9b1f40b30aaac0",
            tokens = AVector((tokenId3, U256.unsafe(8)))
          )
        )

        inputSign(unsignedTx, privKey1)
      }

      tx.verify("multiple-tokens")
    }

    {
      info("with p2sh and p2mpkh transaction inputs")

      val script = {
        val raw =
          s"""
             |// comment
             |AssetScript P2sh {
             |  pub fn main(pubKey1: ByteVec, pubKey2: ByteVec) -> () {
             |    verifyAbsoluteLocktime!(1630879601000)
             |    verifyTxSignature!(pubKey1)
             |    verifyTxSignature!(pubKey2)
             |  }
             |}
             |""".stripMargin

        Compiler.compileAssetScript(raw).rightValue
      }

      val unsignedTx = UnsignedTransaction(
        DefaultTxVersion,
        networkId,
        scriptOpt = None,
        GasBox.unsafe(100000),
        GasPrice(U256.unsafe(1000000000)),
        inputs = AVector(
          TxInput(
            AssetOutputRef.unsafe(
              Hint.unsafe(-1038667625),
              Hash.unsafe(hex"fda5eaacc0fa7bcf60196062509b8167b3d9a4e2711353aec3dc7e66a868621a")
            ),
            UnlockScript.p2mpkh(AVector((pubKey1, 1), (pubKey2, 3)))
          ),
          TxInput(
            AssetOutputRef.unsafe(
              Hint.unsafe(-1038667625),
              Hash.unsafe(hex"b62509167b8c3dc7a5ecc0fa7b3afd6a868621aad9a4e2711353aef601960ce6")
            ),
            UnlockScript
              .p2sh(script, AVector(Val.ByteVec(pubKey1.bytes), Val.ByteVec(pubKey2.bytes)))
          )
        ),
        fixedOutputs = AVector(
          p2shOutput(
            42,
            hex"241ac999a05a2c66c179f1a309dca9c407fa2310d51050b8dfeac11ec0a092fd",
            additionalData = hex"5f7516fc7fac3fd6"
          )
        )
      )

      val tx = inputSign(unsignedTx, privKey1, privKey2, privKey1, privKey2)
      tx.verify("p2sh-and-p2mpkh-inputs")
    }

    {
      info("with contract output")

      val contractLockupScript = LockupScript.P2C(
        Hash.unsafe(hex"0fa5e21a53aef6019606167b3aad9acca7bce6fd6a868642509b8c3dc7e27113")
      )
      val contractAddress = Address.Contract(contractLockupScript).toBase58
      val script = {
        val raw =
          s"""
             |@use(approvedAssets = true, contractAssets = true)
             |TxScript Main {
             |  verifyTxSignature!(#${pubKey1.toHexString})
             |  transferAlphFromSelf!(@$contractAddress, 1000)
             |}
             |""".stripMargin

        Compiler.compileTxScript(raw).rightValue
      }

      val unsignedTx = UnsignedTransaction(
        DefaultTxVersion,
        networkId,
        scriptOpt = Some(script),
        GasBox.unsafe(100000),
        GasPrice(U256.unsafe(1000000000)),
        inputs = AVector(
          TxInput(
            AssetOutputRef.unsafe(
              Hint.unsafe(-1038667625),
              Hash.unsafe(hex"ad9a4e2711353aef6d6a868621a167b3a0196062509b8c3dc7a5ecc0fa7bce6f")
            ),
            UnlockScript.P2PKH(pubKey1)
          )
        ),
        fixedOutputs = AVector.empty
      )

      val transaction        = inputSign(unsignedTx, privKey1)
      val txOutput           = TxOutput.contract(999, contractLockupScript).payGasUnsafe(10)
      val updatedTransaction = transaction.copy(generatedOutputs = AVector(txOutput))

      updatedTransaction.verify("with-contract-output")
    }
  }
}
