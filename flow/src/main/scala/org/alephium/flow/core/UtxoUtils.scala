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

import scala.annotation.tailrec

import org.alephium.protocol.vm.{GasBox, GasPrice}
import org.alephium.util._

/*
 * We sort the utxos based on the amount and type
 *   - the utxos with smaller amounts are selected first
 *   - the utxos with higher persisted level are selected first (confirmed utxos are of high priority)
 */
object UtxoUtils {
  implicit val assetOrder: Ordering[Asset] = (x: Asset, y: Asset) => {
    val compare1 = x.outputType.cachedLevel.compareTo(y.outputType.cachedLevel)
    if (compare1 != 0) {
      compare1
    } else {
      x.output.amount.compareTo(y.output.amount)
    }
  }

  type Asset = FlowUtils.AssetOutputInfo
  final case class Selected(assets: AVector[Asset], gas: GasBox)

  // to select a list of utxos of value (amount + gas fees for inputs and outputs)
  def select(
      utxos: AVector[Asset],
      amount: U256,
      gasPrice: GasPrice,
      gasPerInput: GasBox,
      gasPerOutput: GasBox,
      numOutputs: Int
  ): Either[String, Selected] = {
    val sortedUtxos = utxos.sorted

    for {
      sum_startIndex <- findUtxosWithoutGas(sortedUtxos, amount)
      sum_index <- findUtxosWithGas(
        sortedUtxos,
        sum_startIndex._1,
        sum_startIndex._2,
        amount,
        gasPrice,
        gasPerInput,
        gasPerOutput,
        numOutputs
      )
    } yield {
      val selectedUtxos = sortedUtxos.take(sum_index._2 + 1)
      val gas           = estimateGas(gasPerInput, gasPerOutput, selectedUtxos.length, numOutputs)
      Selected(selectedUtxos, gas)
    }
  }

  def findUtxosWithoutGas(
      sortedUtxos: AVector[Asset],
      amount: U256
  ): Either[String, (U256, Int)] = {
    @tailrec
    def iter(sum: U256, index: Int): (U256, Int) = {
      if (index >= sortedUtxos.length) {
        (sum, -1)
      } else {
        val newSum = sum.addUnsafe(sortedUtxos(index).output.amount)
        if (newSum >= amount) (newSum, index) else iter(newSum, index + 1)
      }
    }

    iter(U256.Zero, 0) match {
      case (sum, -1) => Left(s"Not enough balance: got $sum, expected $amount")
      case result    => Right(result)
    }
  }

  def findUtxosWithGas(
      sortedUtxos: AVector[Asset],
      sum: U256,
      currentIndex: Int,
      amount: U256,
      gasPrice: GasPrice,
      gasPerInput: GasBox,
      gasPerOutput: GasBox,
      numOutputs: Int
  ): Either[String, (U256, Int)] = {
    @tailrec
    def iter(sum: U256, index: Int): (U256, Int) = {
      val gas         = estimateGas(gasPerInput, gasPerOutput, index + 1, numOutputs)
      val gasFee      = gasPrice * gas
      val totalAmount = amount.addUnsafe(gasFee)
      if (sum >= totalAmount) {
        (sum, index)
      } else {
        val nextIndex = index + 1
        if (nextIndex == sortedUtxos.length) {
          (sum, -1)
        } else {
          iter(sum.addUnsafe(sortedUtxos(nextIndex).output.amount), nextIndex)
        }
      }
    }

    iter(sum, currentIndex) match {
      case (_, -1) => Left(s"Not enough balance for fee, maybe transfer a smaller amount")
      case result  => Right(result)
    }
  }

  def estimateGas(
      gasPerInput: GasBox,
      gasPerOutput: GasBox,
      numInputs: Int,
      numOutputs: Int
  ): GasBox = {
    GasBox.unsafe(gasPerInput.value * numInputs + gasPerOutput.value * numOutputs)
  }
}
