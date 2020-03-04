package org.alephium.protocol.script

sealed trait RunFailed
case object InvalidFinalState                    extends RunFailed
case object VerificationFailed                   extends RunFailed
final case class NonCategorized(message: String) extends RunFailed
