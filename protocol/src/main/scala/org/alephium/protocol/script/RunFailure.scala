package org.alephium.protocol.script

sealed trait RunFailure
case object InvalidFinalState                    extends RunFailure
case object VerificationFailed                   extends RunFailure
final case class NonCategorized(message: String) extends RunFailure
case object StackOverflow                        extends RunFailure
case object StackUnderflow                       extends RunFailure
case object InsufficientSignatures               extends RunFailure
case object InvalidPublicKey                     extends RunFailure
