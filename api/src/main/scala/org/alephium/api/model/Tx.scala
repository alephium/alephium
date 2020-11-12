package org.alephium.api.model

import org.alephium.util.AVector
import org.alephium.protocol.model.{NetworkType, Transaction}

final case class Tx(
      hash: String,
      inputs: AVector[Input],
      outputs: AVector[Output]
  )
  object Tx {
    def from(tx: Transaction, networkType: NetworkType): Tx = Tx(
      tx.hash.toHexString,
      tx.unsigned.inputs.map(Input.from),
      tx.unsigned.fixedOutputs.map(Output.from(_, networkType)) ++
        tx.generatedOutputs.map(Output.from(_, networkType))
    )
  }
