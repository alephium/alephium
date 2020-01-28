package org.alephium.protocol.script

sealed trait ExeResult

case object ExeSuccessful extends ExeResult
sealed trait ExeFailed    extends ExeResult

case object InvalidFinalState              extends ExeFailed
case object VerificationFailed             extends ExeFailed
case class NonCategorized(message: String) extends ExeFailed
