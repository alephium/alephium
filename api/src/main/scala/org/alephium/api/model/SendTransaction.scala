package org.alephium.api.model

import org.alephium.protocol.Signature

final case class SendTransaction(tx: String, signature: Signature)
