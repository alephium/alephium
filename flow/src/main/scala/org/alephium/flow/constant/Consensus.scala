package org.alephium.flow.constant
import scala.concurrent.duration._

object Consensus {

  val numZerosAtLeastInHash: Int = 12
  val maxMiningTarget: BigInt    = (BigInt(1) << (256 - numZerosAtLeastInHash)) - 1

  val blockTargetTime: Duration = 4.minutes
  val blockSpanNum: Int         = 15 * 12
}
