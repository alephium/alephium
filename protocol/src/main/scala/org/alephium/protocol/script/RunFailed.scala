package org.alephium.protocol.script

sealed trait RunFailed
case object InvalidFinalState              extends RunFailed
case object VerificationFailed             extends RunFailed
case class NonCategorized(message: String) extends RunFailed
