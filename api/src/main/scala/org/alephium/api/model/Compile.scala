package org.alephium.api.model

import org.alephium.protocol.model.Address

final case class Compile(address: Address, `type`: String, code: String, state: Option[String])
