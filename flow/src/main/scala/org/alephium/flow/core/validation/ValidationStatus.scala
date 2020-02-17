package org.alephium.flow.core.validation

import org.alephium.crypto.Keccak256
import org.alephium.flow.io.{IOError, IOResult}
import org.alephium.protocol.script.RunFailed
import org.alephium.util.AVector

sealed trait ValidationStatus
trait InvalidStatus extends ValidationStatus
trait ValidStatus   extends ValidationStatus

sealed trait BlockStatus        extends ValidationStatus
sealed trait InvalidBlockStatus extends BlockStatus with InvalidStatus
final case object ValidBlock    extends BlockStatus with ValidStatus

sealed trait HeaderStatus        extends ValidationStatus
sealed trait InvalidHeaderStatus extends HeaderStatus with InvalidBlockStatus
final case object ValidHeader    extends HeaderStatus with ValidStatus

// TBD: final case object InvalidBlockSize               extends InvalidBlockStatus
final case object InvalidGroup                           extends InvalidBlockStatus
final case object InvalidTimeStamp                       extends InvalidHeaderStatus
final case object InvalidWorkAmount                      extends InvalidHeaderStatus
final case object InvalidWorkTarget                      extends InvalidHeaderStatus
final case class MissingDeps(hashes: AVector[Keccak256]) extends InvalidHeaderStatus
final case object EmptyTransactionList                   extends InvalidBlockStatus
final case object InvalidCoinbase                        extends InvalidBlockStatus
final case object InvalidMerkleRoot                      extends InvalidBlockStatus

sealed trait InvalidTxsStatus                       extends InvalidBlockStatus
final case class ExistInvalidTx(e: InvalidTxStatus) extends InvalidTxsStatus

object ValidationStatus {
  private[validation] type HeaderValidationResult =
    Either[Either[IOError, InvalidHeaderStatus], Unit]
  private[validation] type BlockValidationError  = Either[IOError, InvalidBlockStatus]
  private[validation] type BlockValidationResult = Either[BlockValidationError, Unit]
  private[validation] type TxsValidationError    = Either[IOError, InvalidTxsStatus]
  private[validation] type TxsValidationResult   = Either[TxsValidationError, Unit]
  private[validation] type TxValidationError     = Either[IOError, InvalidTxStatus]
  private[validation] type TxValidationResult    = Either[TxValidationError, Unit]

  private[validation] def invalidHeader(status: InvalidHeaderStatus): HeaderValidationResult =
    Left(Right(status))
  private[validation] def invalidBlock(status: InvalidBlockStatus): BlockValidationResult =
    Left(Right(status))
  private[validation] def invalidTxs(status: InvalidTxsStatus): TxsValidationResult =
    Left(Right(status))
  private[validation] def invalidTx(status: InvalidTxStatus): TxValidationResult =
    Left(Right(status))
  private[validation] val validHeader: HeaderValidationResult = Right(())
  private[validation] val validBlock: BlockValidationResult   = Right(())
  private[validation] val validTxs: TxsValidationResult       = Right(())
  private[validation] val validTx: TxValidationResult         = Right(())

  private[validation] def convert[T](x: Either[Either[IOError, T], Unit], default: T): IOResult[T] =
    x match {
      case Left(Left(error)) => Left(error)
      case Left(Right(t))    => Right(t)
      case Right(())         => Right(default)
    }

  private[validation] def convert(x: TxValidationResult): TxsValidationResult =
    x match {
      case Left(Left(error)) => Left(Left(error))
      case Left(Right(t))    => Left(Right(ExistInvalidTx(t)))
      case Right(())         => Right(())
    }
}

sealed trait TxStatus        extends ValidationStatus
sealed trait InvalidTxStatus extends TxStatus
final case object ValidTx    extends TxStatus with ValidStatus

final case object EmptyInputs                     extends InvalidTxStatus
final case object EmptyOutputs                    extends InvalidTxStatus
final case object NegativeOutputValue             extends InvalidTxStatus
final case object OutputValueOverFlow             extends InvalidTxStatus
final case object InvalidChainIndex               extends InvalidTxStatus
final case object DoubleSpent                     extends InvalidTxStatus
final case object NonExistInput                   extends InvalidTxStatus
final case object InvalidBalance                  extends InvalidTxStatus
final case object InvalidWitnessLength            extends InvalidTxStatus
final case class InvalidWitness(error: RunFailed) extends InvalidTxStatus
