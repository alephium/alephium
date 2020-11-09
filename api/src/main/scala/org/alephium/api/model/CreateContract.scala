package org.alephium.api.model

import org.alephium.protocol.PublicKey

final case class CreateContract(fromKey: PublicKey, code: String)
