package org.alephium.protocol.config

import org.alephium.protocol.model.Target
import org.alephium.util.Duration

trait ConsensusConfigFixture {
  implicit val consensusConfig: ConsensusConfig = new ConsensusConfig {
    override def numZerosAtLeastInHash: Int = 0
    override def maxMiningTarget: Target    = Target.Max
    override def blockTargetTime: Duration  = Duration.ofMinutesUnsafe(4)
    override def tipsPruneInterval: Int     = 2
  }
}
