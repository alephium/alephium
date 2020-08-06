package org.alephium.crypto

import java.math.BigInteger

import akka.util.ByteString

import org.alephium.util.{AlephiumSpec, AVector}
import org.alephium.util.Hex._

class SecP256K1Spec extends AlephiumSpec {
  def nonCanonical(signature: SecP256K1Signature): SecP256K1Signature = {
    val (r, s) = signature.bytes.splitAt(32)
    val ss     = SecP256K1.params.getN.subtract(new BigInteger(1, s.toArray))
    SecP256K1Signature.unsafe(
      r ++ (ByteString.fromArrayUnsafe(ss.toByteArray).dropWhile(_ equals 0.toByte)))
  }

  it should "derive public key" in {
    def test(rawPrivateKey: ByteString, rawPublicKey: ByteString) = {
      val privateKey = SecP256K1PrivateKey.unsafe(rawPrivateKey)
      val publicKey  = SecP256K1PublicKey.unsafe(rawPublicKey)
      privateKey.publicKey is publicKey
    }

    // see https://en.bitcoin.it/wiki/Technical_background_of_Bitcoin_addresses
    test(
      hex"18E14A7B6A307F426A94F8114701E7C8E774E7F9A47E2C2035DB29A206321725",
      hex"0250863ad64a87ae8a2fe83c1af1a8403cb53f53e486d8511dad8a04887e5b2352"
    )
  }

  it should "be verified with proper public key" in {
    forAll { (message1: AVector[Byte], message2: AVector[Byte]) =>
      whenever(message1 != message2) {
        val (sk1, pk1) = SecP256K1.generatePriPub()
        val (_, pk2)   = SecP256K1.generatePriPub()
        val signature  = SecP256K1.sign(message1, sk1)

        SecP256K1.verify(message1, signature, pk1) is true
        SecP256K1.verify(message2, signature, pk1) is false
        SecP256K1.verify(message1, signature, pk2) is false
        SecP256K1.verify(message1, nonCanonical(signature), pk1) is false
      }
    }
  }
}
