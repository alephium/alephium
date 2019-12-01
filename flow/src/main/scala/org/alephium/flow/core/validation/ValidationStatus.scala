package org.alephium.flow.core.validation

import org.alephium.crypto.Keccak256
import org.alephium.flow.io.{IOError, IOResult}
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

sealed trait InvalidTransactions     extends InvalidBlockStatus
final case object InvalidTxSignature extends InvalidTransactions
final case object InvalidCoin        extends InvalidTransactions
final case object DoubleSpent        extends InvalidTransactions

object ValidationStatus {
  private[validation] type HeaderValidationResult =
    Either[Either[IOError, InvalidHeaderStatus], Unit]
  private[validation] type BlockValidationResult = Either[Either[IOError, InvalidBlockStatus], Unit]

  private[validation] def invalidHeader(status: InvalidHeaderStatus): HeaderValidationResult =
    Left(Right(status))
  private[validation] def invalidBlock(status: InvalidBlockStatus): BlockValidationResult =
    Left(Right(status))
  private[validation] val validHeader: HeaderValidationResult = Right(())
  private[validation] val validBlock: BlockValidationResult   = Right(())

  private[validation] def convert[T](x: Either[Either[IOError, T], Unit], default: T): IOResult[T] =
    x match {
      case Left(Left(error)) => Left(error)
      case Left(Right(t))    => Right(t)
      case Right(())         => Right(default)
    }
}
