package org.alephium.protocol.model

import org.alephium.protocol.{Hash, HashSerde}
import org.alephium.protocol.config.GroupConfig
import org.alephium.protocol.vm.UnlockScript
import org.alephium.serde._
import org.alephium.util.Bytes

final case class TxInput(outputRef: TxOutputRef, unlockScript: UnlockScript)
    extends HashSerde[TxInput] {
  def hash: Hash = _getHash

  def fromGroup(implicit config: GroupConfig): GroupIndex = outputRef.fromGroup
}

object TxInput {
  def unsafe(transaction: Transaction, outputIndex: Int, unlockScript: UnlockScript): TxInput = {
    assume(outputIndex >= 0 && outputIndex < transaction.outputsLength)
    val outputRef = TxOutputRef.unsafe(transaction, outputIndex)
    TxInput(outputRef, unlockScript)
  }

  // Note that the serialization has to put mainKey in the first 32 bytes for the sake of trie indexing
  implicit val serde: Serde[TxInput] =
    Serde.forProduct2(TxInput.apply, ti => (ti.outputRef, ti.unlockScript))
}

trait TxOutputRef {
  def hint: Hint
  def key: Hash

  def isAssetType: Boolean
  def isContractType: Boolean

  def fromGroup(implicit config: GroupConfig): GroupIndex = hint.scriptHint.groupIndex
}

final case class AssetOutputRef private (hint: Hint, key: Hash) extends TxOutputRef {
  override def isAssetType: Boolean    = true
  override def isContractType: Boolean = false
}
object AssetOutputRef {
  def unsafe(hint: Hint, key: Hash): AssetOutputRef = new AssetOutputRef(hint, key)

  def from(scriptHint: ScriptHint, key: Hash): AssetOutputRef =
    unsafe(Hint.ofAsset(scriptHint), key)

  def from(assetOutput: AssetOutput, key: Hash): AssetOutputRef = unsafe(assetOutput.hint, key)
}

final case class ContractOutputRef private (hint: Hint, key: Hash) extends TxOutputRef {
  override def isAssetType: Boolean    = false
  override def isContractType: Boolean = true
}
object ContractOutputRef {
  def unsafe(hint: Hint, key: Hash): ContractOutputRef = new ContractOutputRef(hint, key)

  def from(scriptHint: ScriptHint, key: Hash): ContractOutputRef =
    unsafe(Hint.ofContract(scriptHint), key)

  def from(contractOutput: ContractOutput, key: Hash): ContractOutputRef =
    unsafe(contractOutput.hint, key)
}

object TxOutputRef {
  implicit val serde: Serde[TxOutputRef] =
    Serde.forProduct2(TxOutputRef.from, t => (t.hint, t.key))

  def from(hint: Hint, key: Hash): TxOutputRef = {
    if (hint.isAssetType) AssetOutputRef.unsafe(hint, key) else ContractOutputRef.unsafe(hint, key)
  }

  // Only use this to initialize Merkle tree of ouptuts
  def emptyTreeNode: TxOutputRef =
    ContractOutputRef.unsafe(Hint.ofContract(ScriptHint.fromHash(0)), Hash.zero)

  def key(tx: Transaction, outputIndex: Int): Hash = {
    Hash.hash(tx.hash.bytes ++ Bytes.from(outputIndex))
  }

  def unsafe(transaction: Transaction, outputIndex: Int): TxOutputRef = {
    val refKey = key(transaction, outputIndex)
    TxOutputRef.from(transaction.getOutput(outputIndex).hint, refKey)
  }
}
