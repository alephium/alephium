package org.alephium.flow.core.validation

import org.scalatest.Assertion
import org.scalatest.EitherValues._

import org.alephium.flow.AlephiumFlowSpec
import org.alephium.io.IOResult
import org.alephium.protocol.model.NoIndexModelGeneratorsLike
import org.alephium.util.{Duration, TimeStamp}

class HeaderValidationSpec extends AlephiumFlowSpec with NoIndexModelGeneratorsLike {
  import HeaderValidation._

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

  it should "validate timestamp for block" in {
    val header  = blockGen.sample.get.header
    val now     = TimeStamp.now()
    val before  = (now - Duration.ofMinutesUnsafe(61)).get
    val after   = now + Duration.ofMinutesUnsafe(61)
    val header0 = header.copy(timestamp = now)
    val header1 = header.copy(timestamp = before)
    val header2 = header.copy(timestamp = after)
    passCheck(checkTimeStamp(header0, isSyncing = false))
    failCheck(checkTimeStamp(header1, isSyncing = false), InvalidTimeStamp)
    failCheck(checkTimeStamp(header2, isSyncing = false), InvalidTimeStamp)
    passCheck(checkTimeStamp(header0, isSyncing = true))
    passCheck(checkTimeStamp(header1, isSyncing = true))
    failCheck(checkTimeStamp(header2, isSyncing = true), InvalidTimeStamp)
  }
}
