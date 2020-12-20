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

import org.alephium.protocol.{Hash, HashSerde}
import org.alephium.protocol.config.GroupConfig
import org.alephium.protocol.vm.UnlockScript
import org.alephium.serde._
import org.alephium.util.Bytes

final case class TxInput(outputRef: AssetOutputRef, unlockScript: UnlockScript)
    extends HashSerde[TxInput] {
  def hash: Hash = _getHash

  def fromGroup(implicit config: GroupConfig): GroupIndex = outputRef.fromGroup
}

object TxInput {
  // Note that the serialization has to put outputRef in the first 32 bytes for the sake of trie indexing
  implicit val serde: Serde[TxInput] =
    Serde.forProduct2(TxInput.apply, ti => (ti.outputRef, ti.unlockScript))
}

trait TxOutputRef {
  def hint: Hint
  def key: Hash

  def isAssetType: Boolean
  def isContractType: Boolean
}

final case class AssetOutputRef private (hint: Hint, key: Hash) extends TxOutputRef {
  override def isAssetType: Boolean    = true
  override def isContractType: Boolean = false

  def fromGroup(implicit config: GroupConfig): GroupIndex = hint.scriptHint.groupIndex
}
object AssetOutputRef {
  implicit val serde: Serde[AssetOutputRef] =
    Serde
      .forProduct2[Hint, Hash, AssetOutputRef](unsafe, t => (t.hint, t.key))
      .validate(outputRef =>
        if (outputRef.hint.isAssetType) {
          Right(())
        } else {
          Left("Expect AssetOutputRef, got ContractOutputRef")
      })

  def unsafe(hint: Hint, key: Hash): AssetOutputRef = new AssetOutputRef(hint, key)

  def unsafeWithScriptHint(scriptHint: ScriptHint, key: Hash): AssetOutputRef =
    unsafe(Hint.ofAsset(scriptHint), key)

  // Only use this to initialize Merkle tree of ouptuts
  def forSMT: AssetOutputRef = {
    val hint = Hint.ofAsset(ScriptHint.fromHash(0))
    unsafe(hint, Hash.zero)
  }
}

final case class ContractOutputRef private (hint: Hint, key: Hash) extends TxOutputRef {
  override def isAssetType: Boolean    = false
  override def isContractType: Boolean = true
}
object ContractOutputRef {
  implicit val serde: Serde[ContractOutputRef] =
    Serde
      .forProduct2[Hint, Hash, ContractOutputRef](unsafe, t => (t.hint, t.key))
      .validate(outputRef =>
        if (outputRef.hint.isContractType) {
          Right(())
        } else {
          Left("Expected ContractOutputRef, got AssetOutputRef")
      })

  def unsafe(hint: Hint, key: Hash): ContractOutputRef = new ContractOutputRef(hint, key)

  def unsafe(txId: Hash, contractOutput: ContractOutput, outputIndex: Int): ContractOutputRef = {
    val refKey = TxOutputRef.key(txId, outputIndex)
    unsafe(contractOutput.hint, refKey)
  }

  // Only use this to initialize Merkle tree of ouptuts
  def forSMT: ContractOutputRef = {
    val hint = Hint.ofContract(ScriptHint.fromHash(0))
    unsafe(hint, Hash.zero)
  }
}

object TxOutputRef {
  implicit val serde: Serde[TxOutputRef] =
    Serde.forProduct2[Hint, Hash, TxOutputRef](TxOutputRef.from, t => (t.hint, t.key))

  def from(hint: Hint, key: Hash): TxOutputRef = {
    if (hint.isAssetType) AssetOutputRef.unsafe(hint, key) else ContractOutputRef.unsafe(hint, key)
  }

  def key(txId: Hash, outputIndex: Int): Hash = {
    Hash.hash(txId.bytes ++ Bytes.from(outputIndex))
  }

  def unsafe(transaction: Transaction, outputIndex: Int): TxOutputRef = {
    val refKey = key(transaction.id, outputIndex)
    from(transaction.getOutput(outputIndex), refKey)
  }

  def from(output: TxOutput, key: Hash): TxOutputRef = {
    from(output.hint, key)
  }
}
