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

import com.typesafe.scalalogging.StrictLogging

import org.alephium.flow.gasestimation._
import org.alephium.protocol.config.NetworkConfig
import org.alephium.protocol.model._
import org.alephium.protocol.vm.{GasBox, GasPrice, LockupScript, StatefulScript, UnlockScript}
import org.alephium.util._

/*
 * We sort the Utxos based on the amount and type
 *   - the Utxos with higher persisted level are selected first (confirmed Utxos are of high priority)
 *   - the Utxos with smaller amounts are selected first
 *   - alph selection non-token Utxos first
 *   - the above logic applies to both ALPH and tokens.
 */
// scalastyle:off parameter.number
object UtxoSelectionAlgo extends StrictLogging {
  trait AssetOrder {
    def byAlph: Ordering[Asset]
    def byToken(id: TokenId): Ordering[Asset]
  }

  object AssetAscendingOrder extends AssetOrder {
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
            amountX.compareTo(amountY) match {
              case 0 => byAlph.compare(x, y)
              case v => v
            }
          }
        case (Some(_), None) => -1
        case (None, Some(_)) => 1
        case (None, None)    => byAlph.compare(x, y)
      }
    }
  }

  object AssetDescendingOrder extends AssetOrder {
    val byAlph: Ordering[Asset]               = AssetAscendingOrder.byAlph.reverse
    def byToken(id: TokenId): Ordering[Asset] = AssetAscendingOrder.byToken(id).reverse
  }

  type Asset = FlowUtils.AssetOutputInfo
  final case class Selected(assets: AVector[Asset], gas: GasBox)
  final case class SelectedSoFar(alph: U256, selected: AVector[Asset], rest: AVector[Asset])
  final case class ProvidedGas(
      gasOpt: Option[GasBox],
      gasPrice: GasPrice,
      gasEstimationMultiplier: Option[GasEstimationMultiplier]
  )
  final case class AssetAmounts(alph: U256, tokens: AVector[(TokenId, U256)])
  final case class TxInputWithAsset(input: TxInput, asset: Asset)
  object TxInputWithAsset {
    def from(asset: Asset, unlockScript: UnlockScript): TxInputWithAsset = {
      TxInputWithAsset(TxInput(asset.ref, unlockScript), asset)
    }
  }

  final case class Build(providedGas: ProvidedGas) {
    val ascendingOrderSelector: BuildWithOrder =
      BuildWithOrder(providedGas, AssetAscendingOrder)

    def select(
        amounts: AssetAmounts,
        lockupScript: LockupScript,
        unlockScript: UnlockScript,
        utxos: AVector[Asset],
        txOutputsLength: Int,
        txScriptOpt: Option[StatefulScript],
        assetScriptGasEstimator: AssetScriptGasEstimator,
        txScriptEmulator: TxScriptEmulator
    )(implicit networkConfig: NetworkConfig): Either[String, Selected] = {
      val ascendingResult = ascendingOrderSelector.select(
        amounts,
        lockupScript,
        unlockScript,
        utxos,
        txOutputsLength,
        txScriptOpt,
        assetScriptGasEstimator,
        txScriptEmulator
      )

      ascendingResult match {
        case Right(_) =>
          ascendingResult
        case Left(err) =>
          logger.info(s"Select with ascending order returns $err, try descending order instead")

          val descendingOrderSelector: BuildWithOrder =
            BuildWithOrder(providedGas, AssetDescendingOrder)

          descendingOrderSelector.select(
            amounts,
            lockupScript,
            unlockScript,
            utxos,
            txOutputsLength,
            txScriptOpt,
            assetScriptGasEstimator,
            txScriptEmulator
          )
      }
    }
  }

  final case class BuildWithOrder(
      providedGas: ProvidedGas,
      assetOrder: AssetOrder
  ) {
    def select(
        amounts: AssetAmounts,
        lockupScript: LockupScript,
        unlockScript: UnlockScript,
        utxos: AVector[Asset],
        txOutputsLength: Int,
        txScriptOpt: Option[StatefulScript],
        assetScriptGasEstimator: AssetScriptGasEstimator,
        txScriptEmulator: TxScriptEmulator
    )(implicit networkConfig: NetworkConfig): Either[String, Selected] = {
      val gasPrice = providedGas.gasPrice
      providedGas.gasOpt match {
        case Some(gas) =>
          val amountsWithGas = amounts.copy(alph = amounts.alph.addUnsafe(gasPrice * gas))
          SelectionWithoutGasEstimation(assetOrder)
            .select(amountsWithGas, utxos)
            .map { selectedSoFar =>
              Selected(selectedSoFar.selected, gas)
            }

        case None =>
          for {
            inputWithAssets <- selectTxInputWithAssetsForGasEstimation(amounts, unlockScript, utxos)
            scriptGas <- txScriptOpt match {
              case None =>
                Right(GasBox.zero)
              case Some(txScript) =>
                GasEstimation.estimate(inputWithAssets, txScript, txScriptEmulator).map {
                  scriptGas =>
                    providedGas.gasEstimationMultiplier.map(_ * scriptGas).getOrElse(scriptGas)
                }
            }
            scriptGasFee = gasPrice * scriptGas
            totalAttoAlphAmount <- scriptGasFee
              .add(amounts.alph)
              .toRight("ALPH balance overflow with estimated script gas")
            amountsWithScriptGas = AssetAmounts(totalAttoAlphAmount, amounts.tokens)
            utxosWithGas <- selectUtxos(
              amountsWithScriptGas,
              utxos,
              lockupScript,
              unlockScript,
              txOutputsLength,
              gasPrice,
              assetScriptGasEstimator
            )
          } yield {
            Selected(utxosWithGas._1, utxosWithGas._2.addUnsafe(scriptGas))
          }
      }
    }

    private def selectUtxos(
        assetAmounts: AssetAmounts,
        utxos: AVector[Asset],
        lockupScript: LockupScript,
        unlockScript: UnlockScript,
        txOutputsLength: Int,
        gasPrice: GasPrice,
        assetScriptGasEstimator: AssetScriptGasEstimator
    ): Either[String, (AVector[Asset], GasBox)] = {
      for {
        resultWithoutGas <- SelectionWithoutGasEstimation(assetOrder).select(
          assetAmounts,
          utxos
        )
        resultWithGas <- SelectionWithGasEstimation(gasPrice).select(
          lockupScript,
          unlockScript,
          txOutputsLength,
          resultWithoutGas,
          assetAmounts.alph,
          assetScriptGasEstimator
        )
      } yield (resultWithoutGas.selected ++ resultWithGas._1.selected, resultWithGas._2)
    }

    private def selectTxInputWithAssetsForGasEstimation(
        amounts: AssetAmounts,
        unlockScript: UnlockScript,
        utxos: AVector[Asset]
    )(implicit networkConfig: NetworkConfig): Either[String, AVector[TxInputWithAsset]] = {
      val gasPrice        = providedGas.gasPrice
      val maximalGasPerTx = getMaximalGasPerTx()
      SelectionWithoutGasEstimation(assetOrder)
        .select(
          amounts.copy(alph = amounts.alph.addUnsafe(gasPrice * maximalGasPerTx)),
          utxos
        )
        .map(_.selected.map(TxInputWithAsset.from(_, unlockScript)))
        .orElse(Right(utxos.map(TxInputWithAsset.from(_, unlockScript))))
    }
  }

  final case class SelectionWithoutGasEstimation(assetOrder: AssetOrder) {

    def select(
        amounts: AssetAmounts,
        allUtxos: AVector[Asset]
    ): Either[String, SelectedSoFar] = {
      for {
        tokensFoundResult <- selectForTokens(amounts.tokens, AVector.empty, allUtxos)
        (utxosForTokens, remainingUtxos) = tokensFoundResult
        alphSelected = utxosForTokens.fold(U256.Zero)(_ addUnsafe _.output.amount)
        alphToSelect = amounts.alph.sub(alphSelected).getOrElse(U256.Zero)
        alphFoundResult <- selectForAmount(alphToSelect, sortAlph(remainingUtxos))(asset =>
          Some(asset.output.amount)
        )
      } yield {
        val (utxosForAlph, restOfUtxos) = alphFoundResult
        val foundUtxos                  = utxosForTokens ++ utxosForAlph
        val attoAlphAmountWithoutGas    = foundUtxos.fold(U256.Zero)(_ addUnsafe _.output.amount)
        SelectedSoFar(attoAlphAmountWithoutGas, foundUtxos, restOfUtxos)
      }
    }

    def sortAlph(assets: AVector[Asset]): AVector[Asset] = {
      val assetsWithoutTokens = assets.filter(_.output.tokens.isEmpty)
      val assetsWithTokens    = assets.filter(_.output.tokens.nonEmpty)
      assetsWithoutTokens.sorted(assetOrder.byAlph) ++
        assetsWithTokens.sorted(assetOrder.byAlph)
    }

    private def selectForAmount(
        amount: U256,
        sortedUtxos: AVector[Asset]
    )(getAmount: Asset => Option[U256]): Either[String, (AVector[Asset], AVector[Asset])] = {
      @tailrec
      def iter(sum: U256, index: Int): (U256, Int) = {
        if (index >= sortedUtxos.length) {
          (sum, -1)
        } else {
          val newSum = sum.addUnsafe(getAmount(sortedUtxos(index)).getOrElse(U256.Zero))
          if (newSum >= amount) {
            (newSum, index)
          } else {
            iter(newSum, index + 1)
          }
        }
      }

      if (amount == U256.Zero) {
        Right((AVector.empty, sortedUtxos))
      } else {
        iter(U256.Zero, 0) match {
          case (sum, -1) => Left(s"Not enough balance: got $sum, expected $amount")
          case (_, index) =>
            Right((sortedUtxos.take(index + 1), sortedUtxos.drop(index + 1)))
        }
      }
    }

    @tailrec
    private def selectForTokens(
        totalAmountPerToken: AVector[(TokenId, U256)],
        currentUtxos: AVector[Asset],
        restOfUtxos: AVector[Asset]
    ): Either[String, (AVector[Asset], AVector[Asset])] = {
      if (totalAmountPerToken.isEmpty) {
        Right((currentUtxos, restOfUtxos))
      } else {
        val (tokenId, amount) = totalAmountPerToken.head
        val sortedUtxos       = restOfUtxos.sorted(assetOrder.byToken(tokenId))

        val remainingTokenAmount = calculateRemainingTokensAmount(currentUtxos, tokenId, amount)
        val foundResult = selectForAmount(remainingTokenAmount, sortedUtxos)(
          _.output.tokens.find(_._1 == tokenId).map(_._2)
        )

        foundResult match {
          case Right((foundUtxos, remainingUtxos)) =>
            selectForTokens(totalAmountPerToken.tail, currentUtxos ++ foundUtxos, remainingUtxos)
          case Left(e) =>
            Left(e)
        }
      }
    }

    private def calculateRemainingTokensAmount(
        utxos: AVector[Asset],
        tokenId: TokenId,
        amount: U256
    ): U256 = {
      val amountInUtxo = utxos.fold(U256.Zero) { case (acc, utxo) =>
        utxo.output.tokens.fold(acc) { case (acc1, (id, amount)) =>
          if (tokenId == id) acc1.addUnsafe(amount) else acc1
        }
      }
      amount.sub(amountInUtxo).getOrElse(U256.Zero)
    }
  }

  final case class SelectionWithGasEstimation(gasPrice: GasPrice) {

    def select(
        lockupScript: LockupScript,
        unlockScript: UnlockScript,
        txOutputsLength: Int,
        selectedSoFar: SelectedSoFar,
        totalAttoAlphAmount: U256,
        assetScriptGasEstimator: AssetScriptGasEstimator
    ): Either[String, (SelectedSoFar, GasBox)] = {
      val selectedUTXOs       = selectedSoFar.selected
      val sizeOfSelectedUTXOs = selectedUTXOs.length
      val restOfUtxos         = selectedSoFar.rest

      @tailrec
      def iter(sum: U256, index: Int): Either[String, (U256, Int, GasBox)] = {
        val utxos  = selectedUTXOs ++ restOfUtxos.take(index)
        val inputs = utxos.map(_.ref).map(TxInput(_, unlockScript))

        val estimatedGas = GasEstimation
          .estimateWithInputScript(
            (lockupScript, unlockScript),
            sizeOfSelectedUTXOs + index,
            txOutputsLength,
            assetScriptGasEstimator.setInputs(inputs)
          )

        estimatedGas match {
          case Right(gas) =>
            val gasFee = gasPrice * gas
            if (sum >= totalAttoAlphAmount.addUnsafe(gasFee)) {
              Right((sum, index, gas))
            } else {
              if (index == restOfUtxos.length) {
                Right((sum, -1, gas))
              } else {
                iter(sum.addUnsafe(restOfUtxos(index).output.amount), index + 1)
              }
            }
          case Left(e) =>
            Left(e)
        }
      }

      iter(selectedSoFar.alph, 0).flatMap {
        case (_, -1, _) => Left("Not enough balance for fee, maybe transfer a smaller amount")
        case (sum, index, gas) =>
          Right((SelectedSoFar(sum, restOfUtxos.take(index), restOfUtxos.drop(index)), gas))
      }
    }
  }
}
