package org.alephium.protocol.message

import org.alephium.protocol.message.Payload.Code
import org.alephium.util.{AlephiumSpec, AVector, EnumerationMacros}

class PayloadSpec extends AlephiumSpec {
  implicit val ordering: Ordering[Code] = Ordering.by(Code.toInt(_))

  it should "index all payload types" in {
    val codes = EnumerationMacros.sealedInstancesOf[Code]
    Code.values is AVector.from(codes)
  }
}
