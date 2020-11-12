package org.alephium.api.model

import org.alephium.protocol.model.{Address, NetworkType, TxOutput}
import org.alephium.util.U256

final case class Output(amount: U256, createdHeight: Int, address: Address)
  object Output {
    def from(output: TxOutput, networkType: NetworkType): Output =
      Output(output.amount, output.createdHeight, Address(networkType, output.lockupScript))
  }
