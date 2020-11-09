package org.alephium.api.model

import akka.util.ByteString

import org.alephium.protocol.model.TxInput
import org.alephium.serde.serialize

final case class Input(outputRef: OutputRef, unlockScript: ByteString)

 object Input {
    def from(input: TxInput): Input =
      Input(OutputRef.from(input.outputRef), serialize(input.unlockScript))
  }

