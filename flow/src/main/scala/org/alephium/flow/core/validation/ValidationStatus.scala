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

sealed trait InvalidTransaction                   extends InvalidBlockStatus
final case object InvalidWitnessLength            extends InvalidTransaction
final case class InvalidWitness(error: RunFailed) extends InvalidTransaction
final case object InvalidCoin                     extends InvalidTransaction
final case object DoubleSpent                     extends InvalidTransaction

object ValidationStatus {
  private[validation] type HeaderValidationResult =
    Either[Either[IOError, InvalidHeaderStatus], Unit]
  private[validation] type BlockValidationError  = Either[IOError, InvalidBlockStatus]
  private[validation] type BlockValidationResult = Either[BlockValidationError, Unit]
  private[validation] type TxValidationError     = Either[IOError, InvalidTransaction]
  private[validation] type TxValidationResult    = Either[TxValidationError, Unit]

  private[validation] def invalidHeader(status: InvalidHeaderStatus): HeaderValidationResult =
    Left(Right(status))
  private[validation] def invalidBlock(status: InvalidBlockStatus): BlockValidationResult =
    Left(Right(status))
  private[validation] def invalidTx(status: InvalidTransaction): TxValidationResult =
    Left(Right(status))
  private[validation] val validHeader: HeaderValidationResult = Right(())
  private[validation] val validBlock: BlockValidationResult   = Right(())
  private[validation] val validTx: TxValidationResult         = Right(())

  private[validation] def convert[T](x: Either[Either[IOError, T], Unit], default: T): IOResult[T] =
    x match {
      case Left(Left(error)) => Left(error)
      case Left(Right(t))    => Right(t)
      case Right(())         => Right(default)
    }
}
