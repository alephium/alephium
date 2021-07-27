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

package org.alephium.protocol.vm

import org.alephium.io.IOError
import org.alephium.serde.SerdeError

// scalastyle:off number.of.types
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
case object ExternalPrivateMethodCall                          extends ExeFailure
case object EqualityFailed                                     extends ExeFailure
case object InvalidInstrOffset                                 extends ExeFailure
case object PcOverflow                                         extends ExeFailure
case object InvalidReturnType                                  extends ExeFailure
case object NonEmptyReturnForMainFunction                      extends ExeFailure
final case class InvalidConversion(from: Val, to: Val.Type)    extends ExeFailure
final case class SerdeErrorCreateContract(error: SerdeError)   extends ExeFailure
case object NonExistTxInput                                    extends ExeFailure
case object InvalidContractAddress                             extends ExeFailure
case object UninitializedAddress                               extends ExeFailure
case object NonPayableFrame                                    extends ExeFailure
case object EmptyBalanceForPayableMethod                       extends ExeFailure
case object NotEnoughBalance                                   extends ExeFailure
case object BalanceOverflow                                    extends ExeFailure
case object NoAlfBalanceForTheAddress                          extends ExeFailure
case object NoTokenBalanceForTheAddress                        extends ExeFailure
case object InvalidBalances                                    extends ExeFailure
case object UnableToPayGasFee                                  extends ExeFailure
case object InvalidOutputBalances                              extends ExeFailure
case object InvalidTokenId                                     extends ExeFailure
case object ExpectAContract                                    extends ExeFailure
case object OutOfGas                                           extends ExeFailure
case object ContractPoolOverflow                               extends ExeFailure

sealed trait IOFailure {
  def error: IOError
}
final case class IOErrorUpdateState(error: IOError)  extends IOFailure
final case class IOErrorLoadContract(error: IOError) extends IOFailure
final case class IOErrorLoadOutputs(error: IOError)  extends IOFailure
final case class OtherIOError(error: IOError)        extends IOFailure
