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

import org.alephium.protocol.Hash
import org.alephium.protocol.config.GroupConfig
import org.alephium.protocol.vm.{LockupScript, UnlockScript}
import org.alephium.serde._
import org.alephium.util.Bytes

final case class TxInput(outputRef: AssetOutputRef, unlockScript: UnlockScript) {
  def fromGroup(implicit config: GroupConfig): GroupIndex = outputRef.fromGroup
}

object TxInput {
  // Note that the serialization has to put outputRef in the first 32 bytes for the sake of trie indexing
  implicit val serde: Serde[TxInput] =
    Serde.forProduct2(TxInput.apply, ti => (ti.outputRef, ti.unlockScript))
}

trait TxOutputRef {
  def hint: Hint
  def key: TxOutputRef.Key

  def isAssetType: Boolean
  def isContractType: Boolean
}

final case class AssetOutputRef private (hint: Hint, key: TxOutputRef.Key) extends TxOutputRef {
  override def isAssetType: Boolean    = true
  override def isContractType: Boolean = false

  def fromGroup(implicit config: GroupConfig): GroupIndex = hint.scriptHint.groupIndex

  override def hashCode(): Int = key.hashCode()
  override def equals(obj: Any): Boolean =
    obj match {
      case that: AssetOutputRef => hint == that.hint && key == that.key
      case _                    => false
    }
}
object AssetOutputRef {
  implicit val serde: Serde[AssetOutputRef] =
    Serde
      .forProduct2[Hint, TxOutputRef.Key, AssetOutputRef](unsafe, t => (t.hint, t.key))
      .validate(outputRef =>
        if (outputRef.hint.isAssetType) {
          Right(())
        } else {
          Left("Expect AssetOutputRef, got ContractOutputRef")
        }
      )

  // Hint might not be from script hint
  def unsafe(hint: Hint, key: TxOutputRef.Key): AssetOutputRef = new AssetOutputRef(hint, key)

  def from(scriptHint: ScriptHint, key: TxOutputRef.Key): AssetOutputRef =
    unsafe(Hint.ofAsset(scriptHint), key)

  def from(output: AssetOutput, key: TxOutputRef.Key): AssetOutputRef =
    new AssetOutputRef(output.hint, key)

  // Only use this to initialize Merkle tree of ouptuts
  def forSMT: AssetOutputRef = {
    val hint = Hint.ofAsset(ScriptHint.fromHash(0))
    unsafe(hint, TxOutputRef.unsafeKey(Hash.zero))
  }
}

final case class ContractOutputRef private (hint: Hint, key: TxOutputRef.Key) extends TxOutputRef {
  override def isAssetType: Boolean    = false
  override def isContractType: Boolean = true
}
object ContractOutputRef {
  implicit val serde: Serde[ContractOutputRef] =
    Serde
      .forProduct2[Hint, TxOutputRef.Key, ContractOutputRef](unsafe, t => (t.hint, t.key))
      .validate(outputRef =>
        if (outputRef.hint.isContractType) {
          Right(())
        } else {
          Left("Expected ContractOutputRef, got AssetOutputRef")
        }
      )

  def inaccurateFirstOutput(contractId: ContractId): ContractOutputRef = {
    val outputHint = Hint.ofContract(LockupScript.p2c(contractId).scriptHint)
    new ContractOutputRef(outputHint, TxOutputRef.firstContractOutputKey(contractId))
  }

  // Hint might not be from contract hint
  def unsafe(hint: Hint, key: TxOutputRef.Key): ContractOutputRef = new ContractOutputRef(hint, key)

  def from(
      txId: TransactionId,
      contractOutput: ContractOutput,
      outputIndex: Int
  ): ContractOutputRef = {
    val refKey = TxOutputRef.key(txId, outputIndex)
    new ContractOutputRef(contractOutput.hint, refKey)
  }

  // Only use this to initialize Merkle tree of ouptuts
  def forSMT: ContractOutputRef = {
    val hint = Hint.ofContract(ScriptHint.fromHash(0))
    unsafe(hint, TxOutputRef.unsafeKey(Hash.zero))
  }
}

object TxOutputRef {
  class Key(val value: Hash) extends AnyVal
  implicit val keySerde: Serde[Key] = serdeImpl[Hash].xmap(new Key(_), _.value)

  @inline def key(txId: TransactionId, outputIndex: Int): Key = {
    new Key(Hash.hash(txId.bytes ++ Bytes.from(outputIndex)))
  }
  @inline def firstContractOutputKey(contractId: ContractId): Key = {
    new Key(contractId.value)
  }
  // The hash might not be calculated from (txId, outputIndex) pair
  @inline def unsafeKey(hash: Hash): Key = new Key(hash)

  implicit val serde: Serde[TxOutputRef] =
    Serde.forProduct2[Hint, Key, TxOutputRef](TxOutputRef.from, t => (t.hint, t.key))

  def from(hint: Hint, key: Key): TxOutputRef = {
    if (hint.isAssetType) AssetOutputRef.unsafe(hint, key) else ContractOutputRef.unsafe(hint, key)
  }

  // The output index might not be valid
  def unsafe(transaction: Transaction, outputIndex: Int): TxOutputRef = {
    from(transaction.id, outputIndex, transaction.getOutput(outputIndex))
  }

  def from(txId: TransactionId, outputIndex: Int, output: TxOutput): TxOutputRef = {
    from(output.hint, key(txId, outputIndex))
  }

  def from(output: TxOutput, key: Key): TxOutputRef = {
    from(output.hint, key)
  }
}
