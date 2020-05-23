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
    assume(outputIndex >= 0 && outputIndex < transaction.outputsLength)
    val outputRef = TxOutputRef.unsafe(transaction, outputIndex)
    TxInput(outputRef, unlockScript)
  }

  // Note that the serialization has to put mainKey in the first 32 bytes for the sake of trie indexing
  implicit val serde: Serde[TxInput] =
    Serde.forProduct2(TxInput.apply, ti => (ti.outputRef, ti.unlockScript))
}

trait TxOutputRef {
  def tokenIdOpt: Option[TokenId]
  def scriptHint: Int
  def key: ALF.Hash

  def fromGroup(implicit config: GroupConfig): GroupIndex =
    PubScript.groupIndex(scriptHint)
}

object TxOutputRef {
  implicit val serde: Serde[TxOutputRef] = eitherSerde[AlfOutputRef, TokenOutputRef].xmap({
    case Left(ref)  => ref
    case Right(ref) => ref
  }, {
    case output: AlfOutputRef   => Left(output)
    case output: TokenOutputRef => Right(output)
  })

  def unsafe(transaction: Transaction, outputIndex: Int): TxOutputRef = {
    val output     = transaction.getOutput(outputIndex)
    val outputHash = Hash.hash(transaction.hash.bytes ++ Bits.toBytes(outputIndex))
    output match {
      case o: AlfOutput   => AlfOutputRef(o.scriptHint, outputHash)
      case o: TokenOutput => TokenOutputRef(o.tokenId, o.scriptHint, outputHash)
    }
  }
}

final case class AlfOutputRef(scriptHint: Int, key: ALF.Hash) extends TxOutputRef {
  def tokenIdOpt: Option[TokenId] = None
}

object AlfOutputRef {
  implicit val serde: Serde[AlfOutputRef] =
    Serde.forProduct2(AlfOutputRef.apply, t => (t.scriptHint, t.key))

  def empty: AlfOutputRef = AlfOutputRef(0, ALF.Hash.zero)
}

final case class TokenOutputRef(tokenId: TokenId, scriptHint: Int, key: ALF.Hash)
    extends TxOutputRef {
  def tokenIdOpt: Option[TokenId] = Some(tokenId)
}

object TokenOutputRef {
  implicit val serde: Serde[TokenOutputRef] =
    Serde.forProduct3(TokenOutputRef.apply, t => (t.tokenId, t.scriptHint, t.key))

  def empty: TokenOutputRef = TokenOutputRef(ALF.Hash.zero, 0, ALF.Hash.zero)
}
