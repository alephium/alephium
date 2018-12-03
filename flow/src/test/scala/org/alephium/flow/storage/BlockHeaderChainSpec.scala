package org.alephium.flow.storage

import org.alephium.flow.PlatformConfig
import org.alephium.protocol.model.Block
import org.alephium.util.{AVector, AlephiumSpec}

class BlockHeaderChainSpec extends AlephiumSpec with PlatformConfig.Default {

  it should "calculate target correctly" in {
    val genesis = Block.genesis(AVector.empty, config.maxMiningTarget, 0)
    val gHeader = genesis.header
    val chain   = BlockHeaderChain.fromGenesisUnsafe(genesis)
    chain.getTarget(genesis.header, config.expectedTimeSpan) is gHeader.target
    chain.getTarget(genesis.header, 2 * config.expectedTimeSpan) is (2 * gHeader.target)
    chain.getTarget(genesis.header, config.expectedTimeSpan / 2) is (gHeader.target / 2)
  }
}
