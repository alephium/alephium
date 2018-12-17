package org.alephium.protocol.model

import java.time.Duration

import org.alephium.protocol.config.ConsensusConfig
import org.alephium.serde._
import org.alephium.util.AlephiumSpec
import org.scalatest.TryValues._

class BlockSpec extends AlephiumSpec {

  implicit val config = new ConsensusConfig {
    override val groups: Int = 3

    override def numZerosAtLeastInHash: Int = 0
    override def maxMiningTarget: BigInt    = BigInt(1) << 256 - 1
    override def blockTargetTime: Duration  = Duration.ofMinutes(4)
    override def blockConfirmNum: Int       = 2
    override def retargetInterval: Int      = 180
  }

  behavior of "Block"

  it should "serde" in {
    forAll(ModelGen.blockGen) { block =>
      val bytes  = serialize[Block](block)
      val output = deserialize[Block](bytes).success.value
      output is block
    }
  }

  it should "hash" in {
    forAll(ModelGen.blockGen) { block =>
      block.hash is block.blockHeader.hash
    }
  }
}
