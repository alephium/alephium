package org.alephium.protocol.message

import org.alephium.protocol.config.DiscoveryConfigFixture
import org.scalacheck.Arbitrary
import org.alephium.protocol.model.{ModelGen, PeerId}
import org.alephium.util.{AVector, AlephiumSpec, EnumerationMacros}
import org.scalatest.EitherValues._

class DiscoveryMessageSpec extends AlephiumSpec {
  import DiscoveryMessage.Code

  implicit val peerId: Arbitrary[PeerId]   = Arbitrary(ModelGen.peerId)
  implicit val ordering: Ordering[Code[_]] = Ordering.by(Code.toInt(_))

  behavior of "DiscoveryMessage"

  it should "index all codes" in {
    val codes = EnumerationMacros.sealedInstancesOf[Code[_]]
    Code.values is AVector.from(codes)
  }

  it should "support serde for all message types" in new DiscoveryConfigFixture {
    def groups: Int = 4

    val peerFixture = new DiscoveryConfigFixture { override def groups: Int = 4 }
    forAll(DiscoveryMessageGen.message(peerFixture.config)) { msg =>
      val bytes = DiscoveryMessage.serialize(msg)(peerFixture.config)
      val value = DiscoveryMessage.deserialize(bytes)(config).right.value
      msg == value
    }
  }
}
