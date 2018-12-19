package org.alephium.protocol.message

import org.alephium.protocol.config.DiscoveryConfig
import org.alephium.protocol.message.DiscoveryMessage.Neighbors
import org.scalacheck.Arbitrary
import org.alephium.protocol.model.{ModelGen, PeerId}
import org.alephium.util.{AVector, AlephiumSpec, EnumerationMacros}

class DiscoveryMessageSpec extends AlephiumSpec {
  import DiscoveryMessage.Code

  implicit val peerId: Arbitrary[PeerId]   = Arbitrary(ModelGen.peerId)
  implicit val ordering: Ordering[Code[_]] = Ordering.by(Code.toInt(_))

  behavior of "DiscoveryMessage"

  it should "index all codes" in {
    val codes = EnumerationMacros.sealedInstancesOf[Code[_]]
    Code.values is AVector.from(codes)
  }

  it should "support serde for all message types" in {
    forAll(DiscoveryMessageGen.message) { msg =>
      val bytes = DiscoveryMessage.serialize(msg)
      val config = new DiscoveryConfig {
        override def groups: Int = msg match {
          case neighbors: Neighbors => neighbors.peers.length
          case _                    => 3
        }
        override def peerId: PeerId = PeerId.generate
      }
      val value = DiscoveryMessage.deserialize(bytes)(config).get
      msg == value
    }
  }

}
