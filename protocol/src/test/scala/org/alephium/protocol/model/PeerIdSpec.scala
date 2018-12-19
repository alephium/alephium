package org.alephium.protocol.model

import akka.util.ByteString
import org.alephium.protocol.config.{DiscoveryConfig, GroupConfigFixture}
import org.alephium.serde.Serde
import org.alephium.util.AlephiumSpec
import org.scalatest.EitherValues._

class PeerIdSpec extends AlephiumSpec {

  it should "compute xor distance" in {
    forAll { distance: Long =>
      whenever(distance > 0) {
        val zero          = PeerId.zero
        val value         = Serde.LongSerde.serialize(distance)
        val padding       = ByteString(Array.fill[Byte](PeerId.length - value.size)(0))
        val bytes         = padding ++ value
        val distanceValue = PeerId.serde.deserialize(bytes).right.value

        PeerId.distance(zero, distanceValue) is distance
      }
    }
  }

  it should "compute hamming distance for peer ids" in {
    forAll(ModelGen.peerId, ModelGen.peerId) { (id0, id1) =>
      val output0 = PeerId.hammingDist(id0, id1)
      val output1 = id0.hammingDist(id1)
      val output2 = id1.hammingDist(id0)
      val expected =
        (0 until PeerId.length).map { i =>
          val byte0 = id0.bytes(i) & 0xFF
          val byte1 = id1.bytes(i) & 0xFF
          Integer.bitCount(byte0 ^ byte1)
        }.sum

      output0 is expected
      output1 is expected
      output2 is expected
    }
  }

  it should "compute hamming distance for bytes" in {
    forAll { (byte0: Byte, byte1: Byte) =>
      var xor      = byte0 ^ byte1
      var distance = 0
      (0 until 8) foreach { _ =>
        if (xor % 2 != 0) distance += 1
        xor = xor >> 1
      }
      PeerId.hammingDist(byte0, byte1) is distance
    }
  }

  it should "be able to generate from ED25519 public key" in new GroupConfigFixture {
    override def groups: Int = 9
    (0 until 9).foreach { i =>
      val groupIndex     = GroupIndex(i)
      val (_, publicKey) = DiscoveryConfig.generateDiscoveryKeyPair(groupIndex)
      val peerId         = PeerId.fromPublicKey(publicKey)
      peerId.groupIndex is groupIndex
    }
  }
}
