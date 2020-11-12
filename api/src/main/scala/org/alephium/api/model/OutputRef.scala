package org.alephium.api.model

import org.alephium.protocol.model.TxOutputRef

final case class OutputRef(scriptHint: Int, key: String)
  object OutputRef {
    def from(outputRef: TxOutputRef): OutputRef =
      OutputRef(outputRef.hint.value, outputRef.key.toHexString)
  }
