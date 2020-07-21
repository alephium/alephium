package org.alephium.protocol

import org.scalacheck.Gen

import org.alephium.protocol.config.GroupConfigFixture
import org.alephium.util.AlephiumSpec

class GeneratorsSpec extends AlephiumSpec with Generators with GroupConfigFixture {
  override val groups = 3

  it should "generate random hashes" in {
    val sampleSize = 1000
    val hashes     = Gen.listOfN(sampleSize, hashGen).sample.get
    hashes.toSet.size is sampleSize
  }
}
