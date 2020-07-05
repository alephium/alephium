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

/**
  *
  * @param scriptHint short index of [[LockupScript]] for quick outputs query
  *                   0 is reserved for contract outputs
  * @param key hash of the hash of transaction and index of the [[AssetOutput]];
  *            or hash of the first signature for [[ContractOutput]]
  */
final case class TxOutputRef(scriptHint: Int, key: ALF.Hash) {
  def isContractRef: Boolean = scriptHint == 0

  def fromGroup(implicit config: GroupConfig): GroupIndex =
    LockupScript.groupIndex(scriptHint)
}

object TxOutputRef {
  implicit val serde: Serde[TxOutputRef] =
    Serde.forProduct2(TxOutputRef.apply, t => (t.scriptHint, t.key))

  // Only use this to initialize Merkle tree of ouptuts
  def empty: TxOutputRef = TxOutputRef(0, ALF.Hash.zero)

  def contract(key: Hash): TxOutputRef = TxOutputRef(0, key)

  def unsafe(transaction: Transaction, outputIndex: Int): TxOutputRef = {
    transaction.getOutput(outputIndex) match {
      case output: AssetOutput =>
        val outputHash = Hash.hash(transaction.hash.bytes ++ Bytes.toBytes(outputIndex))
        assume(output.scriptHint != 0)
        TxOutputRef(output.scriptHint, outputHash)
      case output: ContractOutput =>
        // TODO: check non-empty signature in validation
        val outputHash = Hash.hash(transaction.signatures.head.bytes ++ Bytes.toBytes(outputIndex))
        TxOutputRef(output.scriptHint, outputHash)
    }
  }
}
