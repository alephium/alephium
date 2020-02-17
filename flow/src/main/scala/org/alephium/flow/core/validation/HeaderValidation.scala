package org.alephium.flow.core.validation

import org.alephium.flow.core.BlockFlow
import org.alephium.flow.io.IOResult
import org.alephium.flow.platform.PlatformProfile
import org.alephium.protocol.model.BlockHeader

object HeaderValidation extends Validation[BlockHeader, HeaderStatus]() {
  import ValidationStatus.convert

  def validate(header: BlockHeader, flow: BlockFlow, isSyncing: Boolean)(
      implicit config: PlatformProfile
  ): IOResult[HeaderStatus] = {
    convert(Validation.validateHeader(header, flow, isSyncing), ValidHeader)
  }

  def validateUntilDependencies(header: BlockHeader,
                                flow: BlockFlow,
                                isSyncing: Boolean): IOResult[HeaderStatus] = {
    convert(Validation.validateHeaderUntilDependencies(header, flow, isSyncing), ValidHeader)
  }

  def validateAfterDependencies(header: BlockHeader, flow: BlockFlow)(
      implicit config: PlatformProfile): IOResult[HeaderStatus] = {
    convert(Validation.validateHeaderAfterDependencies(header, flow), ValidHeader)
  }
}
