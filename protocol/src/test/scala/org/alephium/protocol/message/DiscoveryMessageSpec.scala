package org.alephium.protocol.message

import akka.util.ByteString
import org.scalacheck.{Arbitrary}

import org.alephium.protocol.model.{ModelGen, PeerId}
import org.alephium.serde.{Deserializer, Serde, Serializer}
import org.alephium.util.{AlephiumSpec, EnumerationMacros}

class DiscoveryMessageSpec extends AlephiumSpec {
  import DiscoveryMessage.Code

  implicit val peerId: Arbitrary[PeerId] = Arbitrary(ModelGen.peerId)
  implicit val ordering: Ordering[Code]  = Ordering.by(Code.toByte(_))

  it should "index all codes" in {
    val codes = EnumerationMacros.sealedInstancesOf[Code]
    Code.values is codes.toList
  }

  it should "support serde for all message types" in {
    forAll(DiscoveryMessageGen.message) { msg =>
      val bytes = Serializer[DiscoveryMessage].serialize(msg)
      val value = Deserializer[DiscoveryMessage].deserialize(bytes).get
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
