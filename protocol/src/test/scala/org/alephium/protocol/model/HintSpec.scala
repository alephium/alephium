// Copyright 2018 The Alephium Authors
// This file is part of the alephium project.
//
// The library is free software: you can redistribute it and/or modify
// it under the terms of the GNU Lesser General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// The library is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
// GNU Lesser General Public License for more details.
//
// You should have received a copy of the GNU Lesser General Public License
// along with the library. If not, see <http://www.gnu.org/licenses/>.

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
