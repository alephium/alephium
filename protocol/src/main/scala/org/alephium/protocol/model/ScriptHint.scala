package org.alephium.protocol.model

import org.alephium.protocol.ALF
import org.alephium.protocol.config.GroupConfig
import org.alephium.util.{Bytes, DjbHash}

class ScriptHint(val value: Int) extends AnyVal {
  def groupIndex(implicit config: GroupConfig): GroupIndex = {
    val hash = Bytes.toPosInt(Bytes.xorByte(value))
    GroupIndex.unsafe(hash % config.groups)
  }
}

object ScriptHint {
  def fromHash(hash: ALF.Hash): ScriptHint = {
    fromHash(DjbHash.intHash(hash.bytes))
  }

  def fromHash(hash: Int): ScriptHint = {
    new ScriptHint(hash | 1)
  }
}
