package org.alephium.protocol.model

import java.time.Duration

import org.alephium.protocol.config.ConsensusConfig

trait ConfigFixture {

  implicit val config = new ConsensusConfig {
    override val groups: Int = 3

    override def numZerosAtLeastInHash: Int = 0
    override def maxMiningTarget: BigInt    = (BigInt(1) << 256) - 1
    override def blockTargetTime: Duration  = Duration.ofMinutes(4)
    override def blockConfirmNum: Int       = 2
    override def retargetInterval: Int      = 180
  }
}
