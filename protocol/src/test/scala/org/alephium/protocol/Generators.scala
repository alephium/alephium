package org.alephium.protocol

import org.scalacheck.Gen

import org.alephium.crypto.{ED25519, ED25519PrivateKey, ED25519PublicKey}
import org.alephium.util.{AlephiumSpec, NumericHelpers}

trait Generators extends NumericHelpers {
  lazy val hashGen: Gen[ALF.Hash] = {
    Gen.const(()).map(_ => ALF.Hash.generate)
  }

  lazy val keypairGen: Gen[(ED25519PrivateKey, ED25519PublicKey)] = {
    Gen.const(()).map(_ => ED25519.generatePriPub())
  }

  lazy val publicKeyGen: Gen[ED25519PublicKey] = keypairGen.map(_._2)
}

class GeneratorsSpec extends AlephiumSpec with Generators {
  it should "generate random hashes" in {
    val sampleSize = 1000
    val hashes     = Gen.listOfN(sampleSize, hashGen).sample.get
    hashes.toSet.size is sampleSize
  }
}
