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

  def unsignedTransaction(
      publicKey: PublicKey,
      scriptOpt: Option[String],
      outputs: AssetOutput*
  ) = {
    import Hex._

    UnsignedTransaction(
      networkId,
      scriptOpt.map(script => Compiler.compileTxScript(script).toOption.value),
      GasBox.unsafe(100000),
      GasPrice(U256.unsafe(1000000000)),
      AVector(
        TxInput(
          AssetOutputRef.unsafe(
            Hint.unsafe(-1038667625),
            Hash.unsafe(hex"a5ecc0fa7bce6fd6a868621a167b3aad9a4e2711353aef60196062509b8c3dc7")
          ),
          p2pkh(publicKey)
        )
      ),
      AVector.from(outputs)
    )
  }

  def coinbaseTransaction(transactions: Transaction*) = {
    Transaction.coinbase(
      ChainIndex.unsafe(0),
      AVector.from(transactions),
      p2pkh(Hash.unsafe(hex"0478042acbc0e37b410e5d2c7aebe367d47f39aa78a65277b7f8bb7ce3c5e036")),
      consensusConfig.maxMiningTarget,
      TimeStamp.unsafe(1629980707001L)
    )
  }

  def p2shOutput(
      alfAmount: U256,
      hash: ByteString,
      timeStamp: TimeStamp = TimeStamp.unsafe(0),
      additionalData: ByteString = ByteString.empty,
      tokens: AVector[(TokenId, U256)] = AVector.empty
  ) = {
    AssetOutput(
      alfAmount,
      p2sh(hash),
      timeStamp,
      tokens,
      additionalData
    )
  }

  def p2pkhOutput(
      alfAmount: U256,
      hash: ByteString,
      timeStamp: TimeStamp = TimeStamp.unsafe(0),
      additionalData: ByteString = ByteString.empty,
      tokens: AVector[(TokenId, U256)] = AVector.empty
  ) = {
    AssetOutput(
      alfAmount,
      p2pkh(Hash.unsafe(hash)),
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
      contractSignatures = AVector.empty
    )
  }

  def contractSign(tx: Transaction, privateKeys: PrivateKey*): Transaction = {
    val signatures = AVector.from(privateKeys).map { privateKey =>
      SignatureSchema.sign(tx.unsigned.hash.bytes, privateKey)
    }

    tx.copy(contractSignatures = tx.contractSignatures ++ signatures)
  }

  def p2sh(bs: ByteString) = {
    LockupScript.P2SH(Hash.unsafe(bs))
  }

  def p2pkh(keyHash: Hash) = {
    LockupScript.P2PKH(keyHash)
  }

  def p2pkh(key: PublicKey) = {
    UnlockScript.P2PKH(key)
  }
}
