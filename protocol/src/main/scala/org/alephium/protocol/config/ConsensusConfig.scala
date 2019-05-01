package org.alephium.protocol.config

import java.time.Duration

trait ConsensusConfig extends GroupConfig {

  def numZerosAtLeastInHash: Int
  def maxMiningTarget: BigInt

  def blockTargetTime: Duration
  def blockConfirmNum: Int
}
