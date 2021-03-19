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
import org.alephium.util._

/*
 * We first travel all utxos and organize them in the state
 *   - if the amout match the target we take it and stop the iteration
 *   - if it smaller we keep the value in our `smallers` list
 *   - if it's bigger, we keep it if it's the smallest bigger
 *
 *  With the `State` we have everything to select the rights utxos
 *  - If we have a matched value, we use only that one
 *  - If the smallers sum match the target we use all of them (for cleaning
 *    and also because it might be a "wallet dump"
 *  - If the smallers sum is less than the target we use the smallest greater (if exist)
 *  - If the smallers sum is bigger than the target, we use them, but clean to keep the
 *    minimum set of utxos (might be changed to use the random combinaison of bitcoin
 *    to avoid the sort)
 *  - Otherwise we return None as we can't reach the target
 */
object UtxoUtils {

  type Asset = (AssetOutputRef, AssetOutput)

  private final case class State(smallers: AVector[Asset],
                                 smallestGreater: Option[Asset],
                                 matching: Option[Asset])

  private def sumAssets(assets: AVector[Asset]): U256 = {
    assets.fold(U256.Zero) { (total, asset) =>
      total.addUnsafe(asset._2.amount)
    }
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
          val amount = asset._2.amount
          if (amount == target) {
            state.copy(matching = Some(asset))
          } else if (amount < target) {
            iter(state.copy(smallers = state.smallers :+ asset), assets.tail)
          } else {
            state.smallestGreater match {
              case Some(currentSmallerGreater) if amount >= currentSmallerGreater._2.amount =>
                iter(state, assets.tail)
              case _ =>
                iter(state.copy(smallestGreater = Some(asset)), assets.tail)
            }
          }
      }
    }

    iter(State(AVector.empty, None, None), initialAssets)
  }

  private def reduceSmallerValue(assets: AVector[Asset],
                                 currentValue: U256,
                                 target: U256): AVector[Asset] = {
    @tailrec
    def iter(sorted: AVector[Asset], value: U256): AVector[Asset] = {
      sorted.headOption.flatMap(asset => value.sub(asset._2.amount)) match {
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
    iter(assets.sortBy(_._2.amount), currentValue)
  }
}
