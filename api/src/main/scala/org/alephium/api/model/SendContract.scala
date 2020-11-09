package org.alephium.api.model

import org.alephium.protocol.Signature

final case class SendContract(code: String, tx: String, signature: Signature, fromGroup: Int)
