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
import org.alephium.protocol.model.{AssetOutputRef, ChainIndex, ModelGenerators, TxGenerators}
import org.alephium.util.{AlephiumSpec, AVector}

class ExtraUtxosInfoSpec extends AlephiumSpec {

  trait UtxoFixture extends FlowFixture with ModelGenerators {
    val chainIndex = ChainIndex.unsafe(0, 0)
    val spentUtxoRefs =
      AVector.from(Gen.nonEmptyListOf(assetOutputRefGen(chainIndex.from)).sample.value)

    def genUtxos(): AVector[AssetOutputInfo] = {
      val assetOutputs =
        AVector.from(Gen.listOfN(spentUtxoRefs.length, assetOutputGen).sample.value)
      assetOutputs.map { assetOutput =>
        val txInputRef = assetOutputRefGen(chainIndex.from).sample.value
        AssetOutputInfo(txInputRef, assetOutput, MemPoolOutput)
      }
    }
  }

  "ExtraUtxosInfo.merge" should "merge UTXOs" in new UtxoFixture {
    val newUtxos       = genUtxos()
    val extraUtxosInfo = ExtraUtxosInfo(newUtxos, spentUtxoRefs)

    val utxos = genUtxos()

    extraUtxosInfo.merge(utxos) is newUtxos ++ utxos

    extraUtxosInfo.merge(
      utxos.replace(0, utxos(0).copy(ref = spentUtxoRefs(0)))
    ) is newUtxos ++ utxos.tail

    val spentUtxos = utxos.zipWithIndex.map { case (utxo, index) =>
      utxo.copy(ref = spentUtxoRefs(index))
    }
    extraUtxosInfo.merge(spentUtxos) is newUtxos
  }

  "ExtraUtxosInfo.updateWithUnsignedTx" should "update UTXO info" in new FlowFixture
    with TxGenerators {
    val chainIndex = chainIndexGen.sample.value

    {
      info("Empty extraUtxosInfo")

      val assetInfos = assetsToSpendGen(scriptGen = p2pkScriptGen(chainIndex.from))
      val unsignedTx = unsignedTxGen(chainIndex)(assetInfos).sample.value

      val extraUtxosInfo        = ExtraUtxosInfo.empty
      val updatedExtraUtxosInfo = extraUtxosInfo.updateWithUnsignedTx(unsignedTx)
      updatedExtraUtxosInfo.newUtxos.map(_.output) is unsignedTx.fixedOutputs
      updatedExtraUtxosInfo.spentUtxos is unsignedTx.inputs.map(_.outputRef)
    }

    {
      info("Non-empty extraUtxosInfo")

      val assetInfos   = assetsToSpendGen(scriptGen = p2pkScriptGen(chainIndex.from))
      val unsignedTx   = unsignedTxGen(chainIndex)(assetInfos).sample.value
      val assetOutputs = AVector.from(Gen.nonEmptyListOf(assetOutputGen).sample.value)

      val utxoRefToBeSpent = unsignedTx.inputs(0).outputRef
      val utxoToBeSpent    = AssetOutputInfo(utxoRefToBeSpent, assetOutputs(0), MemPoolOutput)
      val restOfUtxos = assetOutputs.tail.map { assetOutput =>
        val txInputRef = assetOutputRefGen(chainIndex.from).sample.value
        AssetOutputInfo(txInputRef, assetOutput, MemPoolOutput)
      }

      val alreadySpentUtxos =
        AVector.from(Gen.nonEmptyListOf(assetOutputRefGen(chainIndex.from)).sample.value)

      val extraUtxosInfo = ExtraUtxosInfo(
        newUtxos = utxoToBeSpent +: restOfUtxos,
        spentUtxos = alreadySpentUtxos
      )

      val updatedExtraUtxosInfo = extraUtxosInfo.updateWithUnsignedTx(unsignedTx)
      updatedExtraUtxosInfo.newUtxos
        .filterNot(_.ref == utxoToBeSpent)
        .map(_.output) is restOfUtxos.map(_.output) ++ unsignedTx.fixedOutputs
      updatedExtraUtxosInfo.spentUtxos is alreadySpentUtxos ++ unsignedTx.inputs.map(_.outputRef)
    }
  }

  "ExtraUtxosInfo.updateWithGeneratedOutputs" should "update UTXO info" in new UtxoFixture {

    {
      info("Empty extraUtxosInfo")

      val utxos = genUtxos()

      val extraUtxosInfo        = ExtraUtxosInfo.empty
      val updatedExtraUtxosInfo = extraUtxosInfo.updateWithGeneratedAssetOutputs(utxos)
      updatedExtraUtxosInfo.newUtxos.map(_.output) is utxos.map(_.output)
      updatedExtraUtxosInfo.spentUtxos is AVector.empty[AssetOutputRef]
    }

    {
      info("Non-empty extraUtxosInfo")

      val existingUtxos =
        AVector.from(Gen.nonEmptyListOf(assetOutputGen).sample.value).map { output =>
          val ref = assetOutputRefGen(chainIndex.from).sample.value
          AssetOutputInfo(ref, output, MemPoolOutput)
        }

      val alreadySpentUtxos =
        AVector.from(Gen.nonEmptyListOf(assetOutputRefGen(chainIndex.from)).sample.value)

      val extraUtxosInfo = ExtraUtxosInfo(
        newUtxos = existingUtxos,
        spentUtxos = alreadySpentUtxos
      )

      val utxos = genUtxos()

      val updatedExtraUtxosInfo = extraUtxosInfo.updateWithGeneratedAssetOutputs(utxos)
      updatedExtraUtxosInfo.newUtxos.map(_.output) is existingUtxos.map(_.output) ++ utxos.map(
        _.output
      )
      updatedExtraUtxosInfo.spentUtxos is alreadySpentUtxos
    }
  }
}
