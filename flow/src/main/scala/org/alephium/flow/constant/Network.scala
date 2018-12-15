package org.alephium.flow.constant

import java.time.Duration

import com.typesafe.config.ConfigFactory
import org.alephium.protocol.model.Block

object Network {
  private val config = ConfigFactory.load().getConfig("alephium")

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
        Block.genesis(Seq.empty, Consensus.maxMiningTarget, BigInt(nonce))
    }
  }

  lazy val blocksForFlow: Seq[Seq[Block]] = loadBlockFlow(groups)
}
