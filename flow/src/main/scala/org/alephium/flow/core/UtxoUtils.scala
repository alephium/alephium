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

import org.alephium.util._

/*
 * We first travel all utxos and organize them in the state
 *   - if the amount match the target we take it and stop the iteration
 *   - if it smaller we keep the value in our `smallers` list
 *   - if it's bigger, we keep it if it's the smallest bigger
 *
 *  With the `State` we have everything to select the rights utxos
 *  - If we have a matched value, we use only that one
 *  - If the smallers sum match the target we use all of them (for cleaning
 *    and also because it might be a "wallet dump"
 *  - If the smallers sum is less than the target we use the smallest greater (if exist)
 *  - If the smallers sum is bigger than the target, we use them, but clean to keep the
 *    minimum set of utxos (might be changed to use the random combination of bitcoin
 *    to avoid the sort)
 *  - Otherwise we return None as we can't reach the target
 */
object UtxoUtils {

  type Asset = FlowUtils.AssetOutputInfo
  final case class Selected(assets: AVector[Asset], gasFee: U256)

  final private case class State(
      smallers: AVector[Asset],
      smallestGreater: Option[Asset],
      matching: Option[Asset]
  )

  private def sumAssets(assets: AVector[Asset]): U256 = {
    assets.fold(U256.Zero) { (total, asset) => total.addUnsafe(asset.output.amount) }
  }

  // to select a list of utxos of value (amount + gas fees for inputs and outputs)
  def select(
      utxos: AVector[Asset],
      amount: U256,
      totalGasFee: U256,
      gasFeePerInput: U256,
      gasFeePerOutput: U256,
      numOutputs: Int
  ): Either[String, Selected] = {
    require(numOutputs >= 0)
    for {
      outputGasFee <- gasFeePerOutput
        .mul(U256.unsafe(numOutputs))
        .toRight(s"Output gas fee overflow ($gasFeePerOutput * $numOutputs)")
      selected <- select(utxos, amount, totalGasFee, gasFeePerInput, outputGasFee)
    } yield selected
  }

  @SuppressWarnings(Array("org.wartremover.warts.Recursion"))
  def select(
      utxos: AVector[Asset],
      amount: U256,
      totalGasFee: U256,
      gasFeePerInput: U256,
      outputGasFee: U256
  ): Either[String, Selected] = {
    for {
      totalAmount     <- (amount add totalGasFee).toRight(s"Amount overflow ($amount + $totalGasFee")
      candidateAssets <- select(utxos, totalAmount)
      usedGasFee <- (for {
        inputFees <- gasFeePerInput mul U256.unsafe(candidateAssets.length)
        allGasFee <- inputFees add outputGasFee
      } yield allGasFee)
        .toRight(
          s"Gas fee overflow ($gasFeePerInput * ${candidateAssets.length} + $outputGasFee)"
        )
      assets <-
        if (totalGasFee >= usedGasFee) {
          Right(Selected(candidateAssets, totalGasFee))
        } else {
          select(utxos, amount, usedGasFee, gasFeePerInput, outputGasFee)
        }
    } yield assets
  }

  def select(utxos: AVector[Asset], target: U256): Either[String, AVector[Asset]] = {
    val state = buildState(utxos, target)

    val smallersSum = sumAssets(state.smallers)

    state match {
      case State(_, _, Some(matching)) =>
        Right(AVector(matching))
      case State(smallers, _, _) if smallersSum == target =>
        Right(smallers)
      case State(_, Some(smallestGreater), _) if smallersSum < target =>
        Right(AVector(smallestGreater))
      case State(smallers, _, _) if smallersSum > target =>
        Right(reduceSmallerValue(smallers, smallersSum, target))
      case State(_, Some(smallestGreater), _) => Right(AVector(smallestGreater))
      case _                                  => Left("Not enough balance")
    }
  }

  private def buildState(initialAssets: AVector[Asset], target: U256): State = {
    @tailrec
    def iter(state: State, assets: AVector[Asset]): State = {
      assets.headOption match {
        case None => state
        case Some(asset) =>
          val amount = asset.output.amount
          if (amount == target) {
            state.copy(matching = Some(asset))
          } else if (amount < target) {
            iter(state.copy(smallers = state.smallers :+ asset), assets.tail)
          } else {
            state.smallestGreater match {
              case Some(currentSmallerGreater) if amount >= currentSmallerGreater.output.amount =>
                iter(state, assets.tail)
              case _ =>
                iter(state.copy(smallestGreater = Some(asset)), assets.tail)
            }
          }
      }
    }

    iter(State(AVector.empty, None, None), initialAssets)
  }

  private def reduceSmallerValue(
      assets: AVector[Asset],
      currentValue: U256,
      target: U256
  ): AVector[Asset] = {
    @tailrec
    def iter(sorted: AVector[Asset], value: U256): AVector[Asset] = {
      sorted.headOption.flatMap(asset => value.sub(asset.output.amount)) match {
        case Some(newValue) =>
          if (newValue >= target) {
            iter(sorted.tail, newValue)
          } else {
            sorted
          }
        case None => sorted
      }
    }

    assume(currentValue > target)
    iter(assets.sortBy(_.output.amount), currentValue)
  }
}
