package org.alephium.flow.constant

import java.time.Duration

import com.typesafe.config.ConfigFactory
import org.alephium.flow.client.Miner
import org.alephium.flow.model.ChainIndex
import org.alephium.protocol.model.Block
object Network {
  private val config = ConfigFactory.load().getConfig("alephium")

  val port: Int               = config.getInt("port")
  val pingFrequency: Duration = config.getDuration("pingFrequency")
  val groups: Int             = config.getInt("groups")
  val nonceStep: BigInt       = config.getInt("nonceStep")
  val chainNum                = groups * groups

  def createBlockFlow(groups: Int): Seq[Seq[Block]] = {
    Seq.tabulate(groups, groups) {
      case (from, to) => Miner.mineGenesis(ChainIndex(from, to))
    }
  }

  val blocksForFlow: Seq[Seq[Block]] = createBlockFlow(groups)
}
