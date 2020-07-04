package org.alephium.flow.core.validation

import org.alephium.flow.core.BlockFlow
import org.alephium.flow.platform.PlatformConfig
import org.alephium.protocol.io.IOResult
import org.alephium.protocol.model.BlockHeader

object HeaderValidation extends Validation[BlockHeader, HeaderStatus]() {
  import ValidationStatus.convert

  def validate(header: BlockHeader, flow: BlockFlow, isSyncing: Boolean)(
      implicit config: PlatformConfig
  ): IOResult[HeaderStatus] = {
    convert(Validation.validateHeader(header, flow, isSyncing), ValidHeader)
  }

  def validateUntilDependencies(header: BlockHeader,
                                flow: BlockFlow,
                                isSyncing: Boolean): IOResult[HeaderStatus] = {
    convert(Validation.validateHeaderUntilDependencies(header, flow, isSyncing), ValidHeader)
  }

  def validateAfterDependencies(header: BlockHeader, flow: BlockFlow)(
      implicit config: PlatformConfig): IOResult[HeaderStatus] = {
    convert(Validation.validateHeaderAfterDependencies(header, flow), ValidHeader)
  }
}
