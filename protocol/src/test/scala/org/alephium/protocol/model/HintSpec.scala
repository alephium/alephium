package org.alephium.protocol.model

import org.alephium.util.AlephiumSpec

class HintSpec extends AlephiumSpec {
  it should "present correct types" in {
    forAll { n: Int =>
      val scriptHint = ScriptHint.fromHash(n)

      val hint0 = Hint.ofAsset(scriptHint)
      hint0.isAssetType is true
      hint0.isContractType is false
      hint0.decode is scriptHint -> true

      val hint1 = Hint.ofContract(scriptHint)
      hint1.isAssetType is false
      hint1.isContractType is true
      hint1.decode is scriptHint -> false
    }
  }
}
