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

package org.alephium.api.model

import sttp.tapir.{ValidationResult, Validator}

final case class CounterRange(start: Int, endOpt: Option[Int])

object CounterRange {
  val MaxCounterRange: Int = 100

  val validator: Validator[CounterRange] = Validator.custom { counterRange =>
    if (counterRange.start < 0) {
      ValidationResult.Invalid(s"`start` must not be negative")
    } else {
      counterRange.endOpt match {
        case Some(end) =>
          if (end <= counterRange.start) {
            ValidationResult.Invalid(s"`end` must be larger than `start`")
          } else if (end - counterRange.start > MaxCounterRange) {
            ValidationResult.Invalid(
              s"`end` must be smaller than ${counterRange.start + MaxCounterRange}"
            )
          } else {
            ValidationResult.Valid
          }
        case None =>
          if (counterRange.start > Int.MaxValue - MaxCounterRange) {
            ValidationResult.Invalid(
              s"`start` must be smaller than ${Int.MaxValue - MaxCounterRange}"
            )
          } else {
            ValidationResult.Valid
          }
      }
    }
  }
}
