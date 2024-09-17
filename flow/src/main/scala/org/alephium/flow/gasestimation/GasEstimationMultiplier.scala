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

package org.alephium.flow.gasestimation

import scala.annotation.nowarn

import org.alephium.protocol.vm.GasBox

@nowarn
final case class GasEstimationMultiplier private (value: Double) extends AnyVal {
  def *(gas: GasBox): GasBox = {
    val numerator = (value * GasEstimationMultiplier.Denominator).toInt
    GasBox.unsafe(gas.value * numerator / GasEstimationMultiplier.Denominator)
  }
}

object GasEstimationMultiplier {
  private val MaxPrecision = 2
  private val Denominator  = math.pow(10, GasEstimationMultiplier.MaxPrecision.toDouble).toInt

  def from(multiplier: Option[Double]): Either[String, Option[GasEstimationMultiplier]] = {
    multiplier match {
      case Some(multiplier) => from(multiplier).map(Some(_))
      case None             => Right(None)
    }
  }

  def from(multiplier: Double): Either[String, GasEstimationMultiplier] = {
    if (multiplier < 1.0 || multiplier > 2.0) {
      Left("Invalid gas estimation multiplier, expected a value between [1.0, 2.0]")
    } else {
      val str       = multiplier.toString
      val precision = if (str.contains(".")) str.length - str.indexOf(".") - 1 else 0
      if (precision > MaxPrecision) {
        Left(
          s"Invalid gas estimation multiplier precision, maximum allowed precision is ${MaxPrecision}"
        )
      } else {
        Right(GasEstimationMultiplier(multiplier))
      }
    }
  }
}
