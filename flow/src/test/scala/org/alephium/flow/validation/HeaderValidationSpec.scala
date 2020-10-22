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

package org.alephium.flow.validation

import org.scalatest.Assertion
import org.scalatest.EitherValues._

import org.alephium.flow.AlephiumFlowSpec
import org.alephium.protocol.model.NoIndexModelGeneratorsLike

class HeaderValidationSpec extends AlephiumFlowSpec with NoIndexModelGeneratorsLike {

  def passCheck[T](result: HeaderValidationResult[T]): Assertion = {
    result.isRight is true
  }

  def failCheck[T](result: HeaderValidationResult[T], error: InvalidHeaderStatus): Assertion = {
    result.left.value isE error
  }

  def passValidation(result: HeaderValidationResult[Unit]): Assertion = {
    result.isRight is true
  }

  def failValidation(result: HeaderValidationResult[Unit],
                     error: InvalidHeaderStatus): Assertion = {
    result.left.value isE error
  }

  behavior of "genesis validation"

  // TODO: https://gitlab.com/alephium/alephium-scala-blockflow/-/issues/128

  behavior of "normal header validation"

  // TODO: https://gitlab.com/alephium/alephium-scala-blockflow/-/issues/129
}
