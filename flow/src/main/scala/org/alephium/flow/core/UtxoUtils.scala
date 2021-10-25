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

import org.alephium.protocol.model._
import org.alephium.protocol.vm.{GasBox, GasPrice, GasSchedule}
import org.alephium.util._

/*
 * We sort the Utxos based on the amount and type
 *   - the Utxos with higher persisted level are selected first (confirmed Utxos are of high priority)
 *   - the Utxos with smaller amounts are selected first
 *   - the above logic applies to both ALPH and tokens.
 */
// scalastyle:off parameter.number
object UtxoUtils {
  val assetOrderByAlph: Ordering[Asset] = (x: Asset, y: Asset) => {
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
      case (Some(_), None) => -1
      case (None, Some(_)) => 1
      case (None, None)    => assetOrderByAlph.compare(x, y)
    }
  }

  type Asset = FlowUtils.AssetOutputInfo
  final case class Selected(assets: AVector[Asset], gas: GasBox)

  // to select a list of utxos of value (amount + gas fees for inputs and outputs)
  def select(
      utxos: AVector[Asset],
      totalAlphAmount: U256,
      totalAmountPerToken: AVector[(TokenId, U256)],
      gasOpt: Option[GasBox],
      gasPrice: GasPrice,
      gasPerInput: GasBox,
      gasPerOutput: GasBox,
      dustUtxoAmount: U256,
      numOutputs: Int,
      minimalGas: GasBox
  ): Either[String, Selected] = {
    val sortedUtxosByAlph = utxos.sorted(assetOrderByAlph)

    gasOpt match {
      case Some(gas) =>
        val amountWithGas = totalAlphAmount.addUnsafe(gasPrice * gas)
        findUtxosWithoutGas(sortedUtxosByAlph, amountWithGas, totalAmountPerToken, dustUtxoAmount)
          .map { case (_, selected, _) =>
            Selected(selected, gas)
          }
      case None =>
        select(
          sortedUtxosByAlph,
          totalAlphAmount,
          totalAmountPerToken,
          gasPrice,
          gasPerInput,
          gasPerOutput,
          dustUtxoAmount,
          numOutputs,
          minimalGas
        )
    }
  }

  def validate(sum: U256, amount: U256, dustAmount: U256): Boolean = {
    (sum == amount) || (sum >= amount.addUnsafe(dustAmount))
  }

  def findUtxosWithoutGas(
      sortedUtxos: AVector[Asset],
      alphAmount: U256,
      totalAmountPerToken: AVector[(TokenId, U256)],
      dustUtxoAmount: U256
  ): Either[String, (U256, AVector[Asset], AVector[Asset])] = {
    for {
      alphFoundResult <- findUtxosWithoutGas(sortedUtxos, alphAmount, dustUtxoAmount)(asset =>
        Some(asset.output.amount)
      )
      (alphAmountWithoutGas, utxosForAlph, remainingUtxos) = alphFoundResult
      tokensFoundResult <- findUtxosForTokens(utxosForAlph, remainingUtxos, totalAmountPerToken)
    } yield {
      val (foundUtxos, restOfUtxos, _) = tokensFoundResult
      val alphAmountWithoutGas         = foundUtxos.fold(U256.Zero)(_ addUnsafe _.output.amount)
      (alphAmountWithoutGas, foundUtxos, restOfUtxos)
    }
  }

  // TODO: optimize this method
  def calculateRemainingTokensAmount(
      utxos: AVector[Asset],
      tokenId: TokenId,
      amount: U256
  ): Either[String, U256] = {
    val currentTotalAmountPerTokenE = UnsignedTransaction.calculateTotalAmountPerToken(
      utxos.flatMap(_.output.tokens)
    )
    currentTotalAmountPerTokenE.map { currentTotalAmountPerToken =>
      currentTotalAmountPerToken.find(_._1 == tokenId) match {
        case Some((_, amt)) =>
          amount.sub(amt).getOrElse(U256.Zero)

        case None =>
          amount
      }
    }
  }

  // TODO: improve gas estimator
  def estimateGas(numInputs: Int, numOutputs: Int): GasBox = {
    estimateGas(defaultGasPerInput, defaultGasPerOutput, numInputs, numOutputs, minimalGas)
  }

  def estimateSweepAllTxGas(numInputs: Int): GasBox = {
    val gas = GasSchedule.txBaseGas
      .addUnsafe(GasSchedule.txInputBaseGas.mulUnsafe(numInputs))
      .addUnsafe(GasSchedule.txOutputBaseGas)
      .addUnsafe(GasSchedule.p2pkUnlockGas)
    Math.max(gas, minimalGas)
  }

  private def select(
      sortedUtxos: AVector[Asset],
      totalAlphAmount: U256,
      totalAmountPerToken: AVector[(TokenId, U256)],
      gasPrice: GasPrice,
      gasPerInput: GasBox,
      gasPerOutput: GasBox,
      dustUtxoAmount: U256,
      numOutputs: Int,
      minimalGas: GasBox
  ): Either[String, Selected] = {
    for {
      resultWithoutGas <- findUtxosWithoutGas(
        sortedUtxos,
        totalAlphAmount,
        totalAmountPerToken,
        dustUtxoAmount
      )
      (amountWithoutGas, utxosWithoutGas, restOfUtxos) = resultWithoutGas
      resultForGas <- findUtxosWithGas(
        restOfUtxos,
        amountWithoutGas,
        utxosWithoutGas.length,
        totalAlphAmount,
        gasPrice,
        gasPerInput,
        gasPerOutput,
        dustUtxoAmount,
        numOutputs,
        minimalGas
      )
    } yield {
      val (_, extraUtxosForGas, _) = resultForGas
      val utxos                    = utxosWithoutGas ++ extraUtxosForGas
      val gas                      = estimateGas(gasPerInput, gasPerOutput, utxos.length, numOutputs, minimalGas)
      Selected(utxos, gas)
    }
  }

  @tailrec
  private def findUtxosForTokens(
      currentUtxos: AVector[Asset],
      restOfUtxos: AVector[Asset],
      totalAmountPerToken: AVector[(TokenId, U256)]
  ): Either[String, (AVector[Asset], AVector[Asset], AVector[(TokenId, U256)])] = {
    if (totalAmountPerToken.isEmpty) {
      Right((currentUtxos, restOfUtxos, totalAmountPerToken))
    } else {
      val (tokenId, amount) = totalAmountPerToken.head
      val sortedUtxos       = restOfUtxos.sorted(assetOrderByToken(tokenId))

      val foundResult = for {
        remainingTokenAmount <- calculateRemainingTokensAmount(currentUtxos, tokenId, amount)
        result <- findUtxosWithoutGas(sortedUtxos, remainingTokenAmount, U256.Zero)(
          _.output.tokens.find(_._1 == tokenId).map(_._2)
        )
      } yield result

      foundResult match {
        case Right((_, foundUtxos, otherUtxos)) =>
          findUtxosForTokens(currentUtxos ++ foundUtxos, otherUtxos, totalAmountPerToken.tail)
        case Left(e) =>
          Left(e)
      }
    }
  }

  private def findUtxosWithoutGas(
      sortedUtxos: AVector[Asset],
      amount: U256,
      dustUtxoAmount: U256
  )(getAmount: Asset => Option[U256]): Either[String, (U256, AVector[Asset], AVector[Asset])] = {
    @tailrec
    def iter(sum: U256, index: Int): (U256, Int) = {
      if (index >= sortedUtxos.length) {
        (sum, -1)
      } else {
        val newSum = sum.addUnsafe(getAmount(sortedUtxos(index)).getOrElse(U256.Zero))
        if (validate(newSum, amount, dustUtxoAmount)) {
          (newSum, index)
        } else {
          iter(newSum, index + 1)
        }
      }
    }

    if (amount == U256.Zero) {
      Right((U256.Zero, AVector.empty, sortedUtxos))
    } else {
      iter(U256.Zero, 0) match {
        case (sum, -1)    => Left(s"Not enough balance: got $sum, expected $amount")
        case (sum, index) => Right((sum, sortedUtxos.take(index + 1), sortedUtxos.drop(index + 1)))
      }
    }
  }

  private def estimateGas(
      gasPerInput: GasBox,
      gasPerOutput: GasBox,
      numInputs: Int,
      numOutputs: Int,
      minimalGas: GasBox
  ): GasBox = {
    val gas = GasBox.unsafe(gasPerInput.value * numInputs + gasPerOutput.value * numOutputs)
    Math.max(gas, minimalGas)
  }

  private def findUtxosWithGas(
      restOfUtxos: AVector[Asset],
      currentAlphSum: U256,
      sizeOfSelectedUTXOs: Int,
      totalAlphAmount: U256,
      gasPrice: GasPrice,
      gasPerInput: GasBox,
      gasPerOutput: GasBox,
      dustUtxoAmount: U256,
      numOutputs: Int,
      minimalGas: GasBox
  ): Either[String, (U256, AVector[Asset], AVector[Asset])] = {
    @tailrec
    def iter(sum: U256, index: Int): (U256, Int) = {
      val gas =
        estimateGas(gasPerInput, gasPerOutput, sizeOfSelectedUTXOs + index, numOutputs, minimalGas)
      val gasFee = gasPrice * gas
      if (validate(sum, totalAlphAmount.addUnsafe(gasFee), dustUtxoAmount)) {
        (sum, index)
      } else {
        if (index == restOfUtxos.length) {
          (sum, -1)
        } else {
          iter(sum.addUnsafe(restOfUtxos(index).output.amount), index + 1)
        }
      }
    }

    iter(currentAlphSum, 0) match {
      case (_, -1)      => Left(s"Not enough balance for fee, maybe transfer a smaller amount")
      case (sum, index) => Right((sum, restOfUtxos.take(index), restOfUtxos.drop(index)))
    }
  }
}
