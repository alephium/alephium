package org.alephium.api.model

import org.alephium.protocol.config.GroupConfig
import org.alephium.protocol.model.UnsignedTransaction
import org.alephium.serde.serialize
import org.alephium.util.Hex

final case class CreateContractResult(unsignedTx: String,
                                        hash: String,
                                        fromGroup: Int,
                                        toGroup: Int)
  object CreateContractResult {
    def from(unsignedTx: UnsignedTransaction)(
        implicit groupConfig: GroupConfig): CreateContractResult =
      CreateContractResult(Hex.toHexString(serialize(unsignedTx)),
                           Hex.toHexString(unsignedTx.hash.bytes),
                           unsignedTx.fromGroup.value,
                           unsignedTx.toGroup.value)
  }
