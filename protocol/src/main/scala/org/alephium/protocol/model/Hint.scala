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

import org.alephium.protocol.config.GroupConfig
import org.alephium.serde.Serde
import org.alephium.util.Bytes

// No substypes for the sake of performance
class Hint(val value: Int) extends AnyVal {
  def isAssetType: Boolean = (value & 1) == 1

  def isContractType: Boolean = (value & 1) == 0

  def decode: (ScriptHint, Boolean) = (scriptHint, isAssetType)

  def scriptHint: ScriptHint = new ScriptHint(value | 1)

  def groupIndex(implicit config: GroupConfig): GroupIndex = scriptHint.groupIndex
}

object Hint {
  // We don't use Serde[Int] here as the value of Hint is random, no need of serde optimization
  implicit val serde: Serde[Hint] = Serde
    .bytesSerde(4)
    .xmap(bs => new Hint(Bytes.toIntUnsafe(bs)), hint => Bytes.from(hint.value))

  def from(assetOutput: AssetOutput): Hint = ofAsset(assetOutput.lockupScript.scriptHint)

  def from(contractOutput: ContractOutput): Hint =
    ofContract(contractOutput.lockupScript.scriptHint)

  def ofAsset(scriptHint: ScriptHint): Hint = new Hint(scriptHint.value)

  def ofContract(scriptHint: ScriptHint): Hint = new Hint(scriptHint.value ^ 1)
}
