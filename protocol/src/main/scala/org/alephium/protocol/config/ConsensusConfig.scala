package org.alephium.protocol.config

import org.alephium.protocol.model.Target
import org.alephium.util.Duration

trait ConsensusConfig {

  def numZerosAtLeastInHash: Int
  def maxMiningTarget: Target

  def blockTargetTime: Duration
  // scalastyle:off magic.number
  def maxHeaderTimeStampDrift: Duration = blockTargetTime.timesUnsafe(20)
  // scalastyle:on magic.number

  def tipsPruneInterval: Int
  def tipsPruneDuration: Duration = blockTargetTime.timesUnsafe(tipsPruneInterval.toLong)
}
