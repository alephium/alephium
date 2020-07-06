package org.alephium.protocol.model

import org.alephium.protocol.ALF
import org.alephium.protocol.ALF.Hash
import org.alephium.protocol.config.GroupConfig
import org.alephium.protocol.vm.{LockupScript, UnlockScript}
import org.alephium.serde._
import org.alephium.util.Bytes

final case class TxInput(outputRef: TxOutputRef, unlockScript: UnlockScript) {
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

final case class TxOutputRef(scriptHint: Int, key: ALF.Hash) {
  def tokenIdOpt: Option[TokenId] = None

  def fromGroup(implicit config: GroupConfig): GroupIndex =
    LockupScript.groupIndex(scriptHint)
}

object TxOutputRef {
  implicit val serde: Serde[TxOutputRef] =
    Serde.forProduct2(TxOutputRef.apply, t => (t.scriptHint, t.key))

  def empty: TxOutputRef = TxOutputRef(0, ALF.Hash.zero)

  def unsafe(transaction: Transaction, outputIndex: Int): TxOutputRef = {
    val output     = transaction.getOutput(outputIndex)
    val outputHash = Hash.hash(transaction.hash.bytes ++ Bytes.toBytes(outputIndex))
    TxOutputRef(output.scriptHint, outputHash)
  }
}
