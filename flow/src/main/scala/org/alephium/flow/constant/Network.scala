package org.alephium.flow.constant

import java.time.Duration

import org.alephium.flow.model.ChainIndex
import org.alephium.protocol.model.Block

object Network extends DefaultConfig {

  val port: Int               = config.getInt("port")
  val pingFrequency: Duration = config.getDuration("pingFrequency")
  val groups: Int             = config.getInt("groups")
  val nonceStep: BigInt       = config.getInt("nonceStep")
  val chainNum: Int           = groups * groups

  def loadBlockFlow(groups: Int): Seq[Seq[Block]] = {
    val nonces = config.getStringList("nonces")
    assert(nonces.size == Network.groups * Network.groups)

    Seq.tabulate(groups, groups) {
      case (from, to) =>
        val index = from * Network.groups + to
        val nonce = nonces.get(index)
        val block = Block.genesis(Seq.empty, Consensus.maxMiningTarget, BigInt(nonce))
        assert(ChainIndex(from, to).accept(block.hash))
        block
    }
  }

  lazy val blocksForFlow: Seq[Seq[Block]] = loadBlockFlow(groups)
}
