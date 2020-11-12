package org.alephium.api.model

import org.alephium.protocol.PublicKey
import org.alephium.protocol.model.{Address, NetworkType}
import org.alephium.protocol.vm.LockupScript
import org.alephium.util.U256

 final case class CreateTransaction(
      fromKey: PublicKey,
      toAddress: Address,
      value: U256
  ) {
    def fromAddress(networkType: NetworkType): Address =
      Address(networkType, LockupScript.p2pkh(fromKey))
  }
