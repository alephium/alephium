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

import org.alephium.protocol.model.{defaultGasPerInput, defaultGasPerOutput, minimalGas, TokenId}
import org.alephium.protocol.vm.{GasBox, GasPrice}
import org.alephium.util._

/*
 * We sort the utxos based on the amount and type
 *   - the utxos with higher persisted level are selected first (confirmed utxos are of high priority)
 *   - the utxos with smaller amounts are selected first
 */
// scalastyle:off parameter.number
object UtxoUtils {
  val assetOrderByAlf: Ordering[Asset] = (x: Asset, y: Asset) => {
    val compare1 = x.outputType.cachedLevel.compareTo(y.outputType.cachedLevel)
    if (compare1 != 0) {
      compare1
    } else {
      x.output.amount.compareTo(y.output.amount)
    }
  }

  def assetOrderByToken(id: TokenId): Ordering[Asset] = (x: Asset, y: Asset) => {
    val compare1 = x.outputType.cachedLevel.compareTo(y.outputType.cachedLevel)

    (x.output.tokens.find(_._1 == id), y.output.tokens.find(_._1 == id)) match {
      case (Some((_, amountX)), Some((_, amountY))) =>
        if (compare1 != 0) {
          compare1
        } else {
          amountX.compareTo(amountY)
        }
      case (Some(_), None) => 1
      case (None, Some(_)) => -1
      case (None, None)    => assetOrderByAlf.compare(x, y)
    }
  }

  type Asset = FlowUtils.AssetOutputInfo
  final case class Selected(assets: AVector[Asset], gas: GasBox)

  // to select a list of utxos of value (amount + gas fees for inputs and outputs)
  def select(
      utxos: AVector[Asset],
      totalAlfAmount: U256,
      totalAmountPerToken: AVector[(TokenId, U256)],
      gasOpt: Option[GasBox],
      gasPrice: GasPrice,
      gasPerInput: GasBox,
      gasPerOutput: GasBox,
      dustUtxoAmount: U256,
      numOutputs: Int,
      minimalGas: GasBox
  ): Either[String, Selected] = {
    print(totalAmountPerToken)
    val sortedUtxosByAlf = utxos.sorted(assetOrderByAlf)

    gasOpt match {
      case Some(gas) =>
        val amount = totalAlfAmount.addUnsafe(gasPrice * gas)
        findUtxosWithoutGas(sortedUtxosByAlf, amount, dustUtxoAmount)(_.output.amount)
          .map { case (_, selected, _) =>
            Selected(selected, gas)
          }
      case None =>
        select(
          sortedUtxosByAlf,
          totalAlfAmount,
          gasPrice,
          gasPerInput,
          gasPerOutput,
          dustUtxoAmount,
          numOutputs,
          minimalGas
        )
    }
  }

  def select(
      sortedUtxos: AVector[Asset],
      amount: U256,
      gasPrice: GasPrice,
      gasPerInput: GasBox,
      gasPerOutput: GasBox,
      dustUtxoAmount: U256,
      numOutputs: Int,
      minimalGas: GasBox
  ): Either[String, Selected] = {
    for {
      currentUtxos <- findUtxosWithoutGas(sortedUtxos, amount, dustUtxoAmount)(_.output.amount)
      utxosForGas <- findUtxosWithGas(
        currentUtxos._3,
        currentUtxos._1,
        currentUtxos._2.length,
        amount,
        gasPrice,
        gasPerInput,
        gasPerOutput,
        dustUtxoAmount,
        numOutputs,
        minimalGas
      )
    } yield {
      val selectedUtxos = currentUtxos._2 ++ utxosForGas._2
      val gas           = estimateGas(gasPerInput, gasPerOutput, selectedUtxos.length, numOutputs, minimalGas)
      Selected(selectedUtxos, gas)
    }
  }

  def validate(sum: U256, amount: U256, dustAmount: U256): Boolean = {
    (sum == amount) || (sum >= amount.addUnsafe(dustAmount))
  }

  def findUtxosWithoutGas(
      sortedUtxos: AVector[Asset],
      amount: U256,
      dustUtxoAmount: U256
  )(getAmount: Asset => U256): Either[String, (U256, AVector[Asset], AVector[Asset])] = {
    @tailrec
    def iter(sum: U256, index: Int): (U256, Int) = {
      if (index >= sortedUtxos.length) {
        (sum, -1)
      } else {
        val newSum = sum.addUnsafe(getAmount(sortedUtxos(index)))
        if (validate(newSum, amount, dustUtxoAmount)) {
          (newSum, index)
        } else {
          iter(newSum, index + 1)
        }
      }
    }

    iter(U256.Zero, 0) match {
      case (sum, -1)    => Left(s"Not enough balance: got $sum, expected $amount")
      case (sum, index) => Right((sum, sortedUtxos.take(index + 1), sortedUtxos.drop(index + 1)))
    }
  }

  // scalastyle:off parameter.number
  def findUtxosWithGas(
      sortedUtxos: AVector[Asset],
      sum: U256,
      currentIndex: Int,
      amount: U256,
      gasPrice: GasPrice,
      gasPerInput: GasBox,
      gasPerOutput: GasBox,
      dustUtxoAmount: U256,
      numOutputs: Int,
      minimalGas: GasBox
  ): Either[String, (U256, AVector[Asset], AVector[Asset])] = {
    @tailrec
    def iter(sum: U256, index: Int): (U256, Int) = {
      val gas         = estimateGas(gasPerInput, gasPerOutput, currentIndex + index, numOutputs, minimalGas)
      val gasFee      = gasPrice * gas
      val totalAmount = amount.addUnsafe(gasFee)
      if (validate(sum, totalAmount, dustUtxoAmount)) {
        (sum, index)
      } else {
        if (index == sortedUtxos.length) {
          (sum, -1)
        } else {
          iter(sum.addUnsafe(sortedUtxos(index).output.amount), index + 1)
        }
      }
    }

    iter(sum, 0) match {
      case (_, -1)      => Left(s"Not enough balance for fee, maybe transfer a smaller amount")
      case (sum, index) => Right((sum, sortedUtxos.take(index), sortedUtxos.drop(index)))
    }
  }
  // scalastyle:on parameter.number

  def estimateGas(
      gasPerInput: GasBox,
      gasPerOutput: GasBox,
      numInputs: Int,
      numOutputs: Int,
      minimalGas: GasBox
  ): GasBox = {
    val gas = GasBox.unsafe(gasPerInput.value * numInputs + gasPerOutput.value * numOutputs)
    Math.max(gas, minimalGas)
  }

  def estimateGas(
      numInputs: Int,
      numOutputs: Int
  ): GasBox = {
    estimateGas(defaultGasPerInput, defaultGasPerOutput, numInputs, numOutputs, minimalGas)
  }
}
