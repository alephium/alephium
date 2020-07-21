package org.alephium.protocol.model

import org.alephium.protocol.ALF
import org.alephium.protocol.ALF.Hash
import org.alephium.protocol.config.GroupConfig
import org.alephium.protocol.vm.{LockupScript, UnlockScript}
import org.alephium.serde._
import org.alephium.util.Bytes

final case class TxInput(outputRef: TxOutputRef, unlockScript: UnlockScript)
    extends ALF.HashSerde[TxInput] {
  def hash: Hash = _getHash

  def fromGroup(implicit config: GroupConfig): GroupIndex =
    LockupScript.groupIndex(outputRef.scriptHint)
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

sealed abstract class TxOutputRef {
  def scriptHint: Int
  def key: ALF.Hash
}

/**
  *
  * @param scriptHint short index of LockupScript for quick outputs query
  *                   0 is reserved for contract outputs
  * @param key hash of the hash of transaction and index of the AssetOutput;
  *            or hash of the first signature for ContractOutput
  */
final case class AssetOutputRef(scriptHint: Int, key: ALF.Hash) extends TxOutputRef {
  def fromGroup(implicit config: GroupConfig): GroupIndex = LockupScript.groupIndex(scriptHint)
}
final case class ContractOutputRef(key: ALF.Hash) extends TxOutputRef {
  override def scriptHint: Int = 0
}

object TxOutputRef {
  implicit val serde: Serde[TxOutputRef] =
    Serde.forProduct2(TxOutputRef.from, t => (t.scriptHint, t.key))

  def from(scriptHint: Int, key: ALF.Hash): TxOutputRef = {
    if (scriptHint == 0) ContractOutputRef(key) else AssetOutputRef(scriptHint, key)
  }

  // Only use this to initialize Merkle tree of ouptuts
  def emptyTreeNode: TxOutputRef = AssetOutputRef(1, ALF.Hash.zero)

  def contract(key: Hash): TxOutputRef = ContractOutputRef(key)

  def unsafe(transaction: Transaction, outputIndex: Int): TxOutputRef = {
    transaction.getOutput(outputIndex) match {
      case output: AssetOutput =>
        val outputHash = Hash.hash(transaction.hash.bytes ++ Bytes.toBytes(outputIndex))
        assume(output.scriptHint != 0)
        AssetOutputRef(output.scriptHint, outputHash)
      case _: ContractOutput =>
        // TODO: check non-empty signature in validation
        val outputHash = Hash.hash(transaction.signatures.head.bytes ++ Bytes.toBytes(outputIndex))
        ContractOutputRef(outputHash)
    }
  }
}
