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
        if (outputRef.hint.isAssetType) Right(())
        else Left("Expect AssetOutputRef, got ContractOutputRef"))

  def unsafe(hint: Hint, key: Hash): AssetOutputRef = new AssetOutputRef(hint, key)

  def from(scriptHint: ScriptHint, key: Hash): AssetOutputRef =
    unsafe(Hint.ofAsset(scriptHint), key)

  def from(assetOutput: AssetOutput, key: Hash): AssetOutputRef = unsafe(assetOutput.hint, key)

  // Only use this to initialize Merkle tree of ouptuts
  def forMPT: AssetOutputRef =
    AssetOutputRef.from(ScriptHint.fromHash(0), Hash.zero)
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
        if (outputRef.hint.isContractType) Right(())
        else Left("Expected ContractOutputRef, got AssetOutputRef"))

  def unsafe(hint: Hint, key: Hash): ContractOutputRef = new ContractOutputRef(hint, key)

  def from(contractOutput: ContractOutput, key: Hash): ContractOutputRef =
    unsafe(contractOutput.hint, key)

  // Only use this to initialize Merkle tree of ouptuts
  def forMPT: ContractOutputRef = {
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

  def key(txHash: Hash, outputIndex: Int): Hash = {
    Hash.hash(txHash.bytes ++ Bytes.from(outputIndex))
  }

  def unsafe(transaction: Transaction, outputIndex: Int): TxOutputRef = {
    val refKey = key(transaction.hash, outputIndex)
    from(transaction.getOutput(outputIndex), refKey)
  }

  def from(output: TxOutput, key: Hash): TxOutputRef = {
    from(output.hint, key)
  }
}
