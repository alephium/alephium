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

import org.alephium.protocol._
import org.alephium.protocol.vm.{GasBox, GasPrice, LockupScript, UnlockScript}
import org.alephium.protocol.vm.lang.Compiler
import org.alephium.util.{AVector, Hex, TimeStamp, U256}

trait TransactionSnapshotsFixture extends ModelSnapshots with NoIndexModelGenerators {
  import Hex._

  val pubKey1 =
    PublicKey.unsafe(hex"03d7b2d064a1cf0f55266314dfcd50926ba032069b5c3dda7fd7c83c3ea8055249")
  val privKey1 =
    PrivateKey.unsafe(hex"d803bda2a7b5e2110d1302fe6f9fef18d6b4c38bc4f5e1c31b5830dfb73be216")

  val pubKey2 =
    PublicKey.unsafe(hex"0298d66776af8012ca087214c10f242db3d220f1181ca0cc9f4f6172371f8fae15")
  val privKey2 =
    PrivateKey.unsafe(hex"227cd87dfdbc7e82073d3e05a511ee5c3af2bbd716a498c44e84c098b82be986")

  def unsignedTransaction(
      publicKey: PublicKey,
      scriptOpt: Option[String],
      outputs: AssetOutput*
  ): UnsignedTransaction = {
    import Hex._

    UnsignedTransaction(
      defaultTxVersion,
      networkId,
      scriptOpt.map(script => Compiler.compileTxScript(script).rightValue),
      GasBox.unsafe(100000),
      GasPrice(U256.unsafe(1000000000)),
      AVector(
        TxInput(
          AssetOutputRef.unsafe(
            Hint.unsafe(-1038667625),
            Hash.unsafe(hex"a5ecc0fa7bce6fd6a868621a167b3aad9a4e2711353aef60196062509b8c3dc7")
          ),
          UnlockScript.P2PKH(publicKey)
        )
      ),
      AVector.from(outputs)
    )
  }

  def coinbaseTransaction(transactions: Transaction*) = {
    Transaction.coinbase(
      ChainIndex.unsafe(0),
      AVector.from(transactions),
      LockupScript.P2PKH(
        Hash.unsafe(hex"0478042acbc0e37b410e5d2c7aebe367d47f39aa78a65277b7f8bb7ce3c5e036")
      ),
      consensusConfig.maxMiningTarget,
      TimeStamp.unsafe(1640879601000L)
    )
  }

  def p2shOutput(
      alphAmount: U256,
      hash: ByteString,
      timeStamp: TimeStamp = TimeStamp.unsafe(0),
      additionalData: ByteString = ByteString.empty,
      tokens: AVector[(TokenId, U256)] = AVector.empty
  ) = {
    AssetOutput(
      alphAmount,
      LockupScript.P2SH(Hash.unsafe(hash)),
      timeStamp,
      tokens,
      additionalData
    )
  }

  def p2pkhOutput(
      alphAmount: U256,
      hash: ByteString,
      timeStamp: TimeStamp = TimeStamp.unsafe(0),
      additionalData: ByteString = ByteString.empty,
      tokens: AVector[(TokenId, U256)] = AVector.empty
  ) = {
    AssetOutput(
      alphAmount,
      LockupScript.P2PKH(Hash.unsafe(hash)),
      timeStamp,
      tokens,
      additionalData
    )
  }

  def inputSign(unsignedTx: UnsignedTransaction, privateKeys: PrivateKey*): Transaction = {
    val signatures = AVector.from(privateKeys).map { privateKey =>
      SignatureSchema.sign(unsignedTx.hash.bytes, privateKey)
    }

    Transaction(
      unsignedTx,
      scriptExecutionOk = true,
      contractInputs = AVector.empty,
      generatedOutputs = AVector.empty,
      inputSignatures = signatures,
      scriptSignatures = AVector.empty
    )
  }

  def contractSign(tx: Transaction, privateKeys: PrivateKey*): Transaction = {
    val signatures = AVector.from(privateKeys).map { privateKey =>
      SignatureSchema.sign(tx.unsigned.hash.bytes, privateKey)
    }

    tx.copy(scriptSignatures = tx.scriptSignatures ++ signatures)
  }
}
