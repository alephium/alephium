package org.alephium.protocol.config

import java.time.Duration

trait ConsensusConfig {

  def groups: Int
  def chainNum: Int = groups * groups
  def depsNum: Int  = 2 * groups - 1

  def numZerosAtLeastInHash: Int
  def maxMiningTarget: BigInt

  def blockTargetTime: Duration
  def blockConfirmNum: Int
  def retargetInterval: Int
}
