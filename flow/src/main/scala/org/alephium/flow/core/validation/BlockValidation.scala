package org.alephium.flow.core.validation

import org.alephium.flow.core.BlockFlow
import org.alephium.flow.platform.PlatformConfig
import org.alephium.protocol.io.IOResult
import org.alephium.protocol.model.Block

object BlockValidation extends Validation[Block, BlockStatus]() {
  import ValidationStatus.convert

  def validate(block: Block, flow: BlockFlow, isSyncing: Boolean)(
      implicit config: PlatformConfig): IOResult[BlockStatus] = {
    convert(Validation.validateBlock(block, flow, isSyncing), ValidBlock)
  }

  def validateUntilDependencies(block: Block,
                                flow: BlockFlow,
                                isSyncing: Boolean): IOResult[BlockStatus] = {
    convert(Validation.validateBlockUntilDependencies(block, flow, isSyncing), ValidBlock)
  }

  def validateAfterDependencies(block: Block, flow: BlockFlow)(
      implicit config: PlatformConfig): IOResult[BlockStatus] = {
    convert(Validation.validateBlockAfterDependencies(block, flow), ValidBlock)
  }

  def validateAfterHeader(block: Block, flow: BlockFlow)(
      implicit config: PlatformConfig): IOResult[BlockStatus] = {
    convert(Validation.validateBlockAfterHeader(block, flow), ValidBlock)
  }
}
