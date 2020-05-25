package org.alephium.protocol.vm

trait ExeFailure
case object InvalidFinalState                     extends ExeFailure
case object VerificationFailed                    extends ExeFailure
final case class NonCategorized(message: String)  extends ExeFailure
case object StackOverflow                         extends ExeFailure
case object StackUnderflow                        extends ExeFailure
case object InsufficientSignatures                extends ExeFailure
case object InvalidPublicKey                      extends ExeFailure
case object InvalidBoolean                        extends ExeFailure
case object IntegerOverFlow                       extends ExeFailure
final case class ArithmeticError(message: String) extends ExeFailure
case object TooManyElses                          extends ExeFailure
case object IncompleteIfScript                    extends ExeFailure
final case class InvalidScript(message: String)   extends ExeFailure
case object InvalidScriptHash                     extends ExeFailure
case object InvalidParameters                     extends ExeFailure
case object InvalidLocalIndex                     extends ExeFailure
case object InvalidLocalType                      extends ExeFailure
case object InvalidFieldIndex                     extends ExeFailure
case object InvalidFieldType                      extends ExeFailure
