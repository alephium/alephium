package org.alephium.flow.constant

import java.time.Duration

object Consensus extends DefaultConfig {

  val numZerosAtLeastInHash: Int = config.getInt("numZerosAtLeastInHash")
  val maxMiningTarget: BigInt    = (BigInt(1) << (256 - numZerosAtLeastInHash)) - 1

  val blockTargetTime: Duration = config.getDuration("blockTargetTime")
  val blockConfirmNum: Int      = config.getInt("blockConfirmNum")
  val retargetInterval: Int     = config.getInt("retargetInterval") // number of blocks for retarget
}
