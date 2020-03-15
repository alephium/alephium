//package org.alephium.protocol.message
//
//import org.alephium.protocol.message.Payload.Code
//import org.alephium.util.{AlephiumSpec, AVector, EnumerationMacros}
//
//class PayloadSpec extends AlephiumSpec {
//  implicit val ordering: Ordering[Code[_]] = Ordering.by(Code.toInt(_))
//
//  it should "index all payload types" in {
//    val codes = EnumerationMacros.sealedInstancesOf[Code[_]]
//    Code.values is AVector.from(codes)
//  }
//}
