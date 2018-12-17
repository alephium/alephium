package org.alephium.protocol.message

import akka.util.ByteString
import org.alephium.protocol.config.DiscoveryConfig
import org.alephium.protocol.message.DiscoveryMessage.Neighbors
import org.scalacheck.Arbitrary
import org.alephium.protocol.model.{ModelGen, PeerId}
import org.alephium.serde.Serde
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

  it should "properly compute distance" in {
    forAll { (distance: Long) =>
      whenever(distance > 0) {
        val zero          = PeerId.zero
        val value         = Serde.LongSerde.serialize(distance)
        val padding       = ByteString(Array.fill[Byte](PeerId.length - value.size)(0))
        val bytes         = padding ++ value
        val distanceValue = PeerId.serde.deserialize(bytes).get

        PeerId.distance(zero, distanceValue) is distance
      }
    }
  }
}
