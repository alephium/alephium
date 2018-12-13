package org.alephium.constant

object Consensus {

  val numZerosAtLeastInHash   = 12
  val maxMiningTarget: BigInt = (BigInt(1) << (256 - numZerosAtLeastInHash)) - 1
}
