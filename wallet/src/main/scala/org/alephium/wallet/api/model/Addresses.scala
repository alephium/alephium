package org.alephium.wallet.api.model

import org.alephium.protocol.model.Address
import org.alephium.util.AVector

final case class Addresses(activeAddress: Address, addresses: AVector[Address])
