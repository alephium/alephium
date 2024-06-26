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

package org.alephium.flow.core

import org.scalacheck.Gen

import org.alephium.flow.FlowFixture
import org.alephium.flow.core.FlowUtils.{AssetOutputInfo, MemPoolOutput}
import org.alephium.protocol.model.{ChainIndex, ModelGenerators}
import org.alephium.util.AlephiumSpec
import org.alephium.util.AVector

class ExtraUtxosInfoSpec extends AlephiumSpec {

  "ExtraUtxosInfoSpec.merge" should "merge UTXOs" in new FlowFixture with ModelGenerators  {
    val chainIndex  = ChainIndex.unsafe(0, 0)
    val spentUtxos = AVector.from(Gen.listOf(assetOutputRefGen(chainIndex.from)).sample.value)

    def genUtxos(): AVector[AssetOutputInfo] = {
      val assetOutputs = AVector.from(Gen.nonEmptyListOf(assetOutputGen).sample.value)
      assetOutputs.map { assetOutput =>
        val txInputRef = assetOutputRefGen(chainIndex.from).sample.value
        AssetOutputInfo(txInputRef, assetOutput, MemPoolOutput)
      }
    }
    val newUtxos = genUtxos()
    val extraUtxosInfo = ExtraUtxosInfo(newUtxos, spentUtxos)

    val utxos = genUtxos()
    extraUtxosInfo.merge(utxos.replace(0, utxos(0).copy(ref = spentUtxos(0)))) is utxos.tail ++ newUtxos
    extraUtxosInfo.merge(utxos) is utxos ++ newUtxos
  }
}
