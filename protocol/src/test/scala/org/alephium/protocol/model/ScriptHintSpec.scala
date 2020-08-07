package org.alephium.protocol.model

import org.scalacheck.Gen

import org.alephium.protocol.Hash
import org.alephium.util.AlephiumSpec

class ScriptHintSpec extends AlephiumSpec {
  it should "be 1 in the last bit" in {
    forAll(Gen.const(()).map(_ => Hash.generate)) { hash =>
      val scriptHint = ScriptHint.fromHash(hash)
      (scriptHint.value & 1) is 1
    }
  }
}
