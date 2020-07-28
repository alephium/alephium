package org.alephium.flow.core.validation

import org.alephium.flow.core.{BlockFlow, BlockHeaderChain}
import org.alephium.flow.platform.PlatformConfig
import org.alephium.io.IOResult
import org.alephium.protocol.config.GroupConfig
import org.alephium.protocol.model.{BlockHeader, FlowData}
import org.alephium.util.TimeStamp

trait HeaderValidation extends Validation[BlockHeader, HeaderStatus] {
  import ValidationStatus._

  def validate(header: BlockHeader, flow: BlockFlow, isSyncing: Boolean)(
      implicit config: PlatformConfig
  ): IOResult[HeaderStatus] = {
    convert(checkHeader(header, flow, isSyncing), ValidHeader)
  }

  protected[validation] def checkHeader(header: BlockHeader, flow: BlockFlow, isSyncing: Boolean)(
      implicit config: PlatformConfig): HeaderValidationResult[Unit] = {
    for {
      _ <- checkHeaderUntilDependencies(header, flow, isSyncing)
      _ <- checkHeaderAfterDependencies(header, flow)
    } yield ()
  }

  def validateUntilDependencies(header: BlockHeader,
                                flow: BlockFlow,
                                isSyncing: Boolean): IOResult[HeaderStatus] = {
    convert(checkHeaderUntilDependencies(header, flow, isSyncing), ValidHeader)
  }

  def validateAfterDependencies(header: BlockHeader, flow: BlockFlow)(
      implicit config: PlatformConfig): IOResult[HeaderStatus] = {
    convert(checkHeaderAfterDependencies(header, flow), ValidHeader)
  }

  protected[validation] def checkHeaderUntilDependencies(
      header: BlockHeader,
      flow: BlockFlow,
      isSyncing: Boolean): HeaderValidationResult[Unit] = {
    for {
      _ <- checkTimeStamp(header, isSyncing)
      _ <- checkWorkAmount(header)
      _ <- checkDependencies(header, flow)
    } yield ()
  }

  protected[validation] def checkHeaderAfterDependencies(header: BlockHeader, flow: BlockFlow)(
      implicit config: PlatformConfig): HeaderValidationResult[Unit] = {
    val headerChain = flow.getHeaderChain(header)
    for {
      _ <- checkWorkTarget(header, headerChain)
    } yield ()
  }

  // format off for the sake of reading and checking rules
  // format: off
  protected[validation] def checkTimeStamp(header: BlockHeader, isSyncing: Boolean): HeaderValidationResult[Unit]
  protected[validation] def checkWorkAmount[T <: FlowData](data: T): HeaderValidationResult[Unit]
  protected[validation] def checkDependencies(header: BlockHeader, flow: BlockFlow): HeaderValidationResult[Unit]
  protected[validation] def checkWorkTarget(header: BlockHeader, headerChain: BlockHeaderChain)(implicit config: GroupConfig): HeaderValidationResult[Unit]
  // format: on
}

object HeaderValidation extends HeaderValidation {
  import ValidationStatus._

  @SuppressWarnings(Array("org.wartremover.warts.OptionPartial"))
  protected[validation] def checkTimeStamp(header: BlockHeader,
                                           isSyncing: Boolean): HeaderValidationResult[Unit] = {
    val now      = TimeStamp.now()
    val headerTs = header.timestamp

    val ok1 = headerTs < now.plusHoursUnsafe(1)
    val ok2 = isSyncing || (headerTs > now.plusHoursUnsafe(-1)) // Note: now -1hour is always positive
    if (ok1 && ok2) validHeader(()) else invalidHeader(InvalidTimeStamp)
  }

  protected[validation] def checkWorkAmount[T <: FlowData](
      data: T): HeaderValidationResult[Unit] = {
    val current = BigInt(1, data.hash.bytes.toArray)
    assert(current >= 0)
    if (current <= data.target) validHeader(()) else invalidHeader(InvalidWorkAmount)
  }

  protected[validation] def checkDependencies(header: BlockHeader,
                                              flow: BlockFlow): HeaderValidationResult[Unit] = {
    header.blockDeps.filterNotE(flow.contains) match {
      case Left(error) => Left(Left(error))
      case Right(missings) =>
        if (missings.isEmpty) validHeader(()) else invalidHeader(MissingDeps(missings))
    }
  }

  protected[validation] def checkWorkTarget(header: BlockHeader, headerChain: BlockHeaderChain)(
      implicit config: GroupConfig): HeaderValidationResult[Unit] = {
    headerChain.getHashTarget(header.parentHash) match {
      case Left(error) => Left(Left(error))
      case Right(target) =>
        if (target == header.target) validHeader(()) else invalidHeader(InvalidWorkTarget)
    }
  }
}
