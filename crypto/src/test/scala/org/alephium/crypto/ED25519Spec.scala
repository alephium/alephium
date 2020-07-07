package org.alephium.crypto

import org.alephium.util.{AlephiumSpec, AVector}

class ED25519Spec extends AlephiumSpec {
  "ED25519" should "sign correctly" in {
    forAll { _message: IndexedSeq[Byte] =>
      val message  = AVector.from(_message)
      val (sk, pk) = ED25519.generatePriPub()
      val sign     = ED25519.sign(message, sk)
      ED25519.verify(message, sign, pk) is true
    }
  }

  it should "be verified with proper public key" in {
    forAll { (message1: AVector[Byte], message2: AVector[Byte]) =>
      whenever(message1 != message2) {
        val (sk1, pk1) = ED25519.generatePriPub()
        val (_, pk2)   = ED25519.generatePriPub()
        val signature  = ED25519.sign(message1, sk1)

        ED25519.verify(message1, signature, pk1) is true
        ED25519.verify(message2, signature, pk1) is false
        ED25519.verify(message1, signature, pk2) is false
      }
    }
  }
}
