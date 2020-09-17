package org.alephium.protocol.vm

import org.alephium.io.IOError

trait ExeFailure
case object InvalidFinalState                                  extends ExeFailure
case object VerificationFailed                                 extends ExeFailure
final case class NonCategorized(message: String)               extends ExeFailure
case object StackOverflow                                      extends ExeFailure
case object StackUnderflow                                     extends ExeFailure
case object InsufficientSignatures                             extends ExeFailure
case object InvalidPublicKey                                   extends ExeFailure
case object InvalidBoolean                                     extends ExeFailure
case object IntegerOverFlow                                    extends ExeFailure
final case class ArithmeticError(message: String)              extends ExeFailure
case object TooManyElses                                       extends ExeFailure
case object IncompleteIfScript                                 extends ExeFailure
final case class InvalidScript(message: String)                extends ExeFailure
case object InvalidParameters                                  extends ExeFailure
case object InvalidLocalIndex                                  extends ExeFailure
case object InvalidLocalType                                   extends ExeFailure
case object InvalidFieldIndex                                  extends ExeFailure
case object InvalidFieldType                                   extends ExeFailure
case object NoReturnVal                                        extends ExeFailure
final case class InvalidType(v: Val)                           extends ExeFailure
final case class InvalidMethodIndex(index: Int)                extends ExeFailure
final case class InvalidMethodArgLength(got: Int, expect: Int) extends ExeFailure
case object InvalidMethodParamsType                            extends ExeFailure
case object PrivateExternalMethodCall                          extends ExeFailure
case object EqualityFailed                                     extends ExeFailure
case object InvalidInstrOffset                                 extends ExeFailure
case object PcOverflow                                         extends ExeFailure
case object InvalidReturnType                                  extends ExeFailure
case object NonEmptyReturnForMainFunction                      extends ExeFailure
final case class InvalidConversion(from: Val, to: Val.Type)    extends ExeFailure
final case class IOErrorUpdateState(error: IOError)            extends ExeFailure
final case class IOErrorLoadContract(error: IOError)           extends ExeFailure
case object InvalidContractAddress                             extends ExeFailure
