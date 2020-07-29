package org.alephium.flow.validation

import org.scalatest.Assertion
import org.scalatest.EitherValues._

import org.alephium.flow.AlephiumFlowSpec
import org.alephium.io.IOResult
import org.alephium.protocol.model.NoIndexModelGeneratorsLike

class HeaderValidationSpec extends AlephiumFlowSpec with NoIndexModelGeneratorsLike {

  def passCheck[T](result: HeaderValidationResult[T]): Assertion = {
    result.isRight is true
  }

  def failCheck[T](result: HeaderValidationResult[T], error: InvalidHeaderStatus): Assertion = {
    result.left.value isE error
  }

  def passValidation(result: IOResult[HeaderStatus]): Assertion = {
    result.toOption.get is ValidHeader
  }

  def failValidation(result: IOResult[HeaderStatus], error: InvalidHeaderStatus): Assertion = {
    result.toOption.get is error
  }

  behavior of "genesis validation"

  // TODO: https://gitlab.com/alephium/alephium-scala-blockflow/-/issues/128

  behavior of "normal header validation"

  // TODO: https://gitlab.com/alephium/alephium-scala-blockflow/-/issues/129
}
