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

package org.alephium.flow.validation

import org.alephium.io.{IOError, IOResult}
import org.alephium.protocol.BlockHash
import org.alephium.protocol.vm._
import org.alephium.util.AVector

// scalastyle:off number.of.types

sealed trait InvalidStatus extends Product {
  val name = productPrefix
}

sealed trait InvalidBlockStatus extends InvalidStatus

sealed trait InvalidHeaderStatus extends InvalidBlockStatus

// TBD: final case object InvalidBlockSize               extends InvalidBlockStatus
final case object InvalidGroup                           extends InvalidBlockStatus
final case object InvalidGenesisVersion                  extends InvalidHeaderStatus
final case object InvalidGenesisTimeStamp                extends InvalidHeaderStatus
final case object InvalidGenesisDeps                     extends InvalidHeaderStatus
final case object InvalidGenesisDepStateHash             extends InvalidHeaderStatus
final case object InvalidGenesisWorkAmount               extends InvalidHeaderStatus
final case object InvalidGenesisWorkTarget               extends InvalidHeaderStatus
final case object InvalidBlockVersion                    extends InvalidHeaderStatus
final case object NoIncreasingTimeStamp                  extends InvalidHeaderStatus
final case object EarlierThanLaunchTimeStamp             extends InvalidHeaderStatus
final case object TooAdvancedTimeStamp                   extends InvalidHeaderStatus
final case object InvalidWorkAmount                      extends InvalidHeaderStatus
final case object InvalidPoLWWorkAmount                  extends InvalidHeaderStatus
final case object InvalidWorkTarget                      extends InvalidHeaderStatus
final case object CannotEnablePoLW                       extends InvalidHeaderStatus
final case object InvalidUncleTimeStamp                  extends InvalidHeaderStatus
final case object InvalidHeaderFlow                      extends InvalidHeaderStatus
final case object InvalidDepsNum                         extends InvalidHeaderStatus
final case object InvalidDepsIndex                       extends InvalidHeaderStatus
final case class MissingDeps(hashes: AVector[BlockHash]) extends InvalidHeaderStatus
final case object InvalidDepStateHash                    extends InvalidHeaderStatus
final case class HeaderIOError(e: IOError)               extends InvalidHeaderStatus
final case object EmptyTransactionList                   extends InvalidBlockStatus
final case object TooManyTransactions                    extends InvalidBlockStatus
final case object TxGasPriceNonDecreasing                extends InvalidBlockStatus
final case object TooMuchGasUsed                         extends InvalidBlockStatus
final case object InvalidCoinbaseFormat                  extends InvalidBlockStatus
final case object InvalidCoinbaseData                    extends InvalidBlockStatus
final case object InvalidCoinbaseReward                  extends InvalidBlockStatus
final case object InvalidCoinbaseLockedAmount            extends InvalidBlockStatus
final case object InvalidCoinbaseLockupPeriod            extends InvalidBlockStatus
final case object InvalidTxsMerkleRoot                   extends InvalidBlockStatus
final case object BlockDoubleSpending                    extends InvalidBlockStatus
final case class ExistInvalidTx(e: InvalidTxStatus)      extends InvalidBlockStatus
final case object InvalidFlowDeps                        extends InvalidBlockStatus
final case object InvalidFlowTxs                         extends InvalidBlockStatus

object ValidationStatus {
  private[validation] def invalidHeader[T](status: InvalidHeaderStatus): HeaderValidationResult[T] =
    Left(Right(status))
  private[validation] def invalidBlock[T](status: InvalidBlockStatus): BlockValidationResult[T] =
    Left(Right(status))
  private[validation] def invalidTx[T](status: InvalidTxStatus): TxValidationResult[T] =
    Left(Right(status))
  private[validation] def validHeader[T](t: T): HeaderValidationResult[T] = Right(t)
  private[validation] def validBlock[T](t: T): BlockValidationResult[T]   = Right(t)
  private[validation] def validTx[T](t: T): TxValidationResult[T]         = Right(t)

  private[validation] def from[Invalid, T](
      result: IOResult[T]
  ): Either[Either[IOError, Invalid], T] = {
    result match {
      case Right(t)    => Right(t)
      case Left(error) => Left(Left(error))
    }
  }

  @inline private[validation] def fromExeResult[T](
      result: ExeResult[T],
      wrapper: ExeFailure => InvalidTxStatus
  ): TxValidationResult[T] = {
    result match {
      case Right(value)       => validTx(value)
      case Left(Right(error)) => invalidTx(wrapper(error))
      case Left(Left(error)) =>
        error.error match {
          case e: IOError.KeyNotFound => invalidTx(wrapper(UncaughtKeyNotFoundError(e)))
          case e: IOError.Serde       => invalidTx(wrapper(UncaughtSerdeError(e)))
          case e                      => Left(Left(e))
        }
    }
  }

  @inline private[validation] def fromOption[T](
      result: Option[T],
      error: InvalidTxStatus
  ): TxValidationResult[T] = {
    result.toRight(Right(error))
  }

  private[validation] def convert[T](x: Either[Either[IOError, T], Unit], default: T): IOResult[T] =
    x match {
      case Left(Left(error)) => Left(error)
      case Left(Right(t))    => Right(t)
      case Right(())         => Right(default)
    }

  private[validation] def convert[T](x: TxValidationResult[T]): BlockValidationResult[T] =
    x match {
      case Left(Left(error)) => Left(Left(error))
      case Left(Right(t))    => Left(Right(ExistInvalidTx(t)))
      case Right(t)          => Right(t)
    }
}

sealed trait InvalidTxStatus extends InvalidStatus

final case object InvalidTxVersion                              extends InvalidTxStatus
final case object InvalidNetworkId                              extends InvalidTxStatus
final case object TooManyInputs                                 extends InvalidTxStatus
final case object ContractInputForInterGroupTx                  extends InvalidTxStatus
final case object NoOutputs                                     extends InvalidTxStatus
final case object TooManyOutputs                                extends InvalidTxStatus
final case object GeneratedOutputForInterGroupTx                extends InvalidTxStatus
final case object InvalidStartGas                               extends InvalidTxStatus
final case object InvalidGasPrice                               extends InvalidTxStatus
final case object InvalidOutputStats                            extends InvalidTxStatus
final case object DuplicatedInputs                              extends InvalidTxStatus
final case object InvalidInputGroupIndex                        extends InvalidTxStatus
final case object InvalidOutputGroupIndex                       extends InvalidTxStatus
final case object TxDoubleSpending                              extends InvalidTxStatus
final case object OutputDataSizeExceeded                        extends InvalidTxStatus
final case object NonExistInput                                 extends InvalidTxStatus
final case object TimeLockedTx                                  extends InvalidTxStatus
final case object InvalidAlphBalance                            extends InvalidTxStatus
final case object InvalidTokenBalance                           extends InvalidTxStatus
final case object BalanceOverFlow                               extends InvalidTxStatus
final case object InvalidWitnessLength                          extends InvalidTxStatus
final case object InvalidPublicKeyHash                          extends InvalidTxStatus
final case object InvalidScriptHash                             extends InvalidTxStatus
final case object InvalidSignature                              extends InvalidTxStatus
final case object TooManyInputSignatures                        extends InvalidTxStatus
final case object TooManyScriptSignatures                       extends InvalidTxStatus
final case object UnexpectedScriptSignatures                    extends InvalidTxStatus
final case object InvalidNumberOfPublicKey                      extends InvalidTxStatus
final case object InvalidP2mpkhUnlockScript                     extends InvalidTxStatus
final case object OutOfGas                                      extends InvalidTxStatus
final case object NotEnoughSignature                            extends InvalidTxStatus
final case object InvalidUnlockScriptType                       extends InvalidTxStatus
final case class InvalidUnlockScript(error: ExeFailure)         extends InvalidTxStatus
final case object CreateContractWithOldId                       extends InvalidTxStatus
final case class WorldStateIOError(error: IOError)              extends InvalidTxStatus
final case object UnexpectedTxScript                            extends InvalidTxStatus
final case class UnlockScriptExeFailed(error: ExeFailure)       extends InvalidTxStatus
final case class TxScriptExeFailed(error: ExeFailure)           extends InvalidTxStatus
final case object ContractInputsShouldBeEmptyForFailedTxScripts extends InvalidTxStatus
final case object InvalidContractInputs                         extends InvalidTxStatus
final case object InvalidGeneratedOutputs                       extends InvalidTxStatus
final case object InvalidRemainingBalancesForFailedScriptTx     extends InvalidTxStatus
final case object InvalidScriptExecutionFlag                    extends InvalidTxStatus
