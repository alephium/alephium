package org.alephium.protocol.script

sealed trait RunFailure
case object InvalidFinalState                     extends RunFailure
case object VerificationFailed                    extends RunFailure
final case class NonCategorized(message: String)  extends RunFailure
case object StackOverflow                         extends RunFailure
case object StackUnderflow                        extends RunFailure
case object IndexOverflow                         extends RunFailure
case object IndexUnderflow                        extends RunFailure
case object InsufficientSignatures                extends RunFailure
case object InvalidPublicKey                      extends RunFailure
case object InvalidBoolean                        extends RunFailure
case object IntegerOverFlow                       extends RunFailure
final case class ArithmeticError(message: String) extends RunFailure
case object TooManyElses                          extends RunFailure
case object IncompleteIfScript                    extends RunFailure
