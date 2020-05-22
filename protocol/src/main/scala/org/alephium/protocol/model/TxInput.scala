package org.alephium.protocol.model

import org.alephium.protocol.ALF
import org.alephium.protocol.ALF.Hash
import org.alephium.protocol.config.GroupConfig
import org.alephium.protocol.script.{PubScript, Script}
import org.alephium.serde._
import org.alephium.util.Bits

final case class TxInput(outputRef: TxOutputRef, unlockScript: Script) {
  def fromGroup(implicit config: GroupConfig): GroupIndex =
    PubScript.groupIndex(outputRef.scriptHint)
}

object TxInput {
  def unsafe(transaction: Transaction, outputIndex: Int, unlockScript: Script): TxInput = {
    assume(outputIndex >= 0 && outputIndex < transaction.unsigned.outputs.length)
    val outputRef = TxOutputRef.unsafe(transaction, outputIndex)
    TxInput(outputRef, unlockScript)
  }

  // Note that the serialization has to put mainKey in the first 32 bytes for the sake of trie indexing
  implicit val serde: Serde[TxInput] =
    Serde.forProduct2(TxInput.apply, ti => (ti.outputRef, ti.unlockScript))
}

final case class TxOutputRef(tokenIdOpt: Option[TokenId], scriptHint: Int, key: ALF.Hash) {
  def fromGroup(implicit config: GroupConfig): GroupIndex =
    PubScript.groupIndex(scriptHint)
}

object TxOutputRef {
  implicit val serde: Serde[TxOutputRef] =
    Serde.forProduct3(TxOutputRef.apply, t => (t.tokenIdOpt, t.scriptHint, t.key))

  def unsafe(transaction: Transaction, outputIndex: Int): TxOutputRef = {
    val output     = transaction.unsigned.outputs(outputIndex)
    val outputHash = Hash.hash(transaction.hash.bytes ++ Bits.toBytes(outputIndex))
    TxOutputRef(output.tokenIdOpt, output.scriptHint, outputHash)
  }
}
