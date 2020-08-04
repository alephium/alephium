package org.alephium.protocol.config

import org.alephium.util.Duration

trait ConsensusConfig extends GroupConfig {

  def numZerosAtLeastInHash: Int
  def maxMiningTarget: BigInt

  def blockTargetTime: Duration
  // scalastyle:off magic.number
  def maxHeaderTimeStampDrift: Duration = blockTargetTime.timesUnsafe(20)
  // scalastyle:on magic.number

  def tipsPruneInterval: Int
  def tipsPruneDuration: Duration = blockTargetTime.timesUnsafe(tipsPruneInterval.toLong)
}
