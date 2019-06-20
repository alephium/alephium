package org.alephium.protocol.config
import java.time.Duration

trait ConsensusConfigFixture {

  implicit val consensusConfig = new ConsensusConfig {
    override val groups: Int = 3

    override def numZerosAtLeastInHash: Int = 0
    override def maxMiningTarget: BigInt    = (BigInt(1) << 256) - 1
    override def blockTargetTime: Duration  = Duration.ofMinutes(4)
    override def blockConfirmNum: Int       = 2
  }
}
