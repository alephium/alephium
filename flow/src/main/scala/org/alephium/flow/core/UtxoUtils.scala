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

import org.alephium.flow.gasestimation._
import org.alephium.protocol.model._
import org.alephium.protocol.vm.{GasBox, GasPrice, UnlockScript}
import org.alephium.util._

/*
 * We sort the Utxos based on the amount and type
 *   - the Utxos with higher persisted level are selected first (confirmed Utxos are of high priority)
 *   - the Utxos with smaller amounts are selected first
 *   - the above logic applies to both ALPH and tokens.
 */
// scalastyle:off parameter.number
object UtxoUtils {
  object AssetAscendingOrder {
    val byAlph: Ordering[Asset] = (x: Asset, y: Asset) => {
      val compare1 = x.outputType.cachedLevel.compareTo(y.outputType.cachedLevel)
      if (compare1 != 0) {
        compare1
      } else {
        x.output.amount.compareTo(y.output.amount)
      }
    }

    def byToken(id: TokenId): Ordering[Asset] = (x: Asset, y: Asset) => {
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
        case (None, None)    => byAlph.compare(x, y)
      }
    }
  }

  type Asset = FlowUtils.AssetOutputInfo
  final case class Selected(assets: AVector[Asset], gas: GasBox)
  final case class SelectedSoFar(alph: U256, selected: AVector[Asset], rest: AVector[Asset])
  final case class ProvidedGas(
      gasOpt: Option[GasBox],
      gasPriceOpt: Option[GasPrice]
  )
  final case class Amounts(alph: U256, tokens: AVector[(TokenId, U256)])

  final case class Selection(
      unlockScript: UnlockScript,
      utxos: AVector[Asset],
      txOutputsLength: Int,
      providedGas: ProvidedGas,
      estimatedTxScriptGas: Option[GasBox],
      dustAmount: U256,
      assetScriptGasEstimator: AssetScriptGasEstimator
  ) {
    def select(amounts: Amounts): Either[String, Selected] = {
      val sortedUtxos = utxos.sorted(AssetAscendingOrder.byAlph)
      val gasPrice    = providedGas.gasPriceOpt.getOrElse(defaultGasPrice)

      providedGas.gasOpt match {
        case Some(gas) =>
          val amountsWithGas = amounts.copy(alph = amounts.alph.addUnsafe(gasPrice * gas))
          SelectionWithoutGasEstimation
            .select(sortedUtxos, amountsWithGas, dustAmount)
            .map { selectedSoFar =>
              Selected(selectedSoFar.selected, gas)
            }

        case None =>
          val scriptGas    = estimatedTxScriptGas.getOrElse(GasBox.zero)
          val scriptGasFee = gasPrice * scriptGas
          for {
            totalAlphAmount <- scriptGasFee
              .add(amounts.alph)
              .toRight("ALPH balance overflow with estimated script gas")
            amountsWithScriptGas = Amounts(totalAlphAmount, amounts.tokens)
            resultWithoutGas <- SelectionWithoutGasEstimation.select(
              sortedUtxos,
              amountsWithScriptGas,
              dustAmount
            )
            resultForGas <- SelectionWithGasEstimation.select(
              unlockScript,
              txOutputsLength,
              resultWithoutGas,
              totalAlphAmount,
              gasPrice,
              dustAmount,
              assetScriptGasEstimator
            )
          } yield {
            val utxos = resultWithoutGas.selected ++ resultForGas.selected
            val gas = GasEstimation.estimateWithInputScript(
              unlockScript,
              utxos.length,
              txOutputsLength,
              assetScriptGasEstimator
            )
            Selected(utxos, gas.addUnsafe(scriptGas))
          }
      }
    }
  }

  object SelectionWithoutGasEstimation {

    def select(
        sortedUtxos: AVector[Asset],
        amounts: Amounts,
        dustAmount: U256
    ): Either[String, SelectedSoFar] = {
      for {
        alphFoundResult <- selectForAlph(sortedUtxos, amounts.alph, dustAmount)(asset =>
          Some(asset.output.amount)
        )
        (alphAmountWithoutGas, utxosForAlph, remainingUtxos) = alphFoundResult
        tokensFoundResult <- selectForTokens(utxosForAlph, remainingUtxos, amounts.tokens)
      } yield {
        val (foundUtxos, restOfUtxos, _) = tokensFoundResult
        val alphAmountWithoutGas         = foundUtxos.fold(U256.Zero)(_ addUnsafe _.output.amount)
        SelectedSoFar(alphAmountWithoutGas, foundUtxos, restOfUtxos)
      }
    }

    private def selectForAlph(
        sortedUtxos: AVector[Asset],
        amount: U256,
        dustAmount: U256
    )(getAmount: Asset => Option[U256]): Either[String, (U256, AVector[Asset], AVector[Asset])] = {
      @tailrec
      def iter(sum: U256, index: Int): (U256, Int) = {
        if (index >= sortedUtxos.length) {
          (sum, -1)
        } else {
          val newSum = sum.addUnsafe(getAmount(sortedUtxos(index)).getOrElse(U256.Zero))
          if (validate(newSum, amount, dustAmount)) {
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
          case (sum, -1) => Left(s"Not enough balance: got $sum, expected $amount")
          case (sum, index) =>
            Right((sum, sortedUtxos.take(index + 1), sortedUtxos.drop(index + 1)))
        }
      }
    }

    @tailrec
    private def selectForTokens(
        currentUtxos: AVector[Asset],
        restOfUtxos: AVector[Asset],
        totalAmountPerToken: AVector[(TokenId, U256)]
    ): Either[String, (AVector[Asset], AVector[Asset], AVector[(TokenId, U256)])] = {
      if (totalAmountPerToken.isEmpty) {
        Right((currentUtxos, restOfUtxos, totalAmountPerToken))
      } else {
        val (tokenId, amount) = totalAmountPerToken.head
        val sortedUtxos       = restOfUtxos.sorted(AssetAscendingOrder.byToken(tokenId))

        val foundResult = for {
          remainingTokenAmount <- calculateRemainingTokensAmount(currentUtxos, tokenId, amount)
          result <- selectForAlph(sortedUtxos, remainingTokenAmount, U256.Zero)(
            _.output.tokens.find(_._1 == tokenId).map(_._2)
          )
        } yield result

        foundResult match {
          case Right((_, foundUtxos, remainingUtxos)) =>
            selectForTokens(currentUtxos ++ foundUtxos, remainingUtxos, totalAmountPerToken.tail)
          case Left(e) =>
            Left(e)
        }
      }
    }

    // TODO: optimize this method
    private def calculateRemainingTokensAmount(
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
  }

  object SelectionWithGasEstimation {

    def select(
        unlockScript: UnlockScript,
        txOutputsLength: Int,
        selectedSoFar: SelectedSoFar,
        totalAlphAmount: U256,
        gasPrice: GasPrice,
        dustAmount: U256,
        assetScriptGasEstimator: AssetScriptGasEstimator
    ): Either[String, SelectedSoFar] = {
      val selectedUTXOs       = selectedSoFar.selected
      val sizeOfSelectedUTXOs = selectedUTXOs.length
      val restOfUtxos         = selectedSoFar.rest

      @tailrec
      def iter(sum: U256, index: Int): (U256, Int) = {
        val utxos  = selectedUTXOs ++ restOfUtxos.take(index)
        val inputs = utxos.map(_.ref).map(TxInput(_, unlockScript))
        val gas = GasEstimation.estimateWithInputScript(
          unlockScript,
          sizeOfSelectedUTXOs + index,
          txOutputsLength,
          assetScriptGasEstimator.setInputs(inputs)
        )
        val gasFee = gasPrice * gas
        if (validate(sum, totalAlphAmount.addUnsafe(gasFee), dustAmount)) {
          (sum, index)
        } else {
          if (index == restOfUtxos.length) {
            (sum, -1)
          } else {
            iter(sum.addUnsafe(restOfUtxos(index).output.amount), index + 1)
          }
        }
      }

      iter(selectedSoFar.alph, 0) match {
        case (_, -1) => Left(s"Not enough balance for fee, maybe transfer a smaller amount")
        case (sum, index) =>
          Right(SelectedSoFar(sum, restOfUtxos.take(index), restOfUtxos.drop(index)))
      }
    }
  }

  def validate(sum: U256, amount: U256, dustAmount: U256): Boolean = {
    (sum == amount) || (sum >= amount.addUnsafe(dustAmount))
  }
}
