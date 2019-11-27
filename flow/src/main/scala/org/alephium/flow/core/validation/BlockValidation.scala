package org.alephium.flow.core.validation

import org.alephium.flow.core.BlockFlow
import org.alephium.flow.io.IOResult
import org.alephium.flow.platform.PlatformProfile
import org.alephium.protocol.model.Block

object BlockValidation extends Validation[Block, BlockStatus]() {
  import ValidationStatus._

  def validate(block: Block, flow: BlockFlow, isSyncing: Boolean)(
      implicit config: PlatformProfile): IOResult[BlockStatus] = {
    convert(Validation.validateBlock(block, flow, isSyncing), ValidBlock)
  }

  def validateUntilDependencies(block: Block,
                                flow: BlockFlow,
                                isSyncing: Boolean): IOResult[BlockStatus] = {
    convert(Validation.validateBlockUntilDependencies(block, flow, isSyncing), ValidBlock)
  }

  def validateAfterDependencies(block: Block, flow: BlockFlow)(
      implicit config: PlatformProfile): IOResult[BlockStatus] = {
    convert(Validation.validateBlockAfterDependencies(block, flow), ValidBlock)
  }

  def validateAfterHeader(block: Block, flow: BlockFlow)(
      implicit config: PlatformProfile): IOResult[BlockStatus] = {
    convert(Validation.validateBlockAfterHeader(block, flow), ValidBlock)
  }
}
