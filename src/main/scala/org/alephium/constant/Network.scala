package org.alephium.constant

import java.net.InetSocketAddress
import java.time.Duration

import com.typesafe.config.ConfigFactory
import org.alephium.client.Miner
import org.alephium.protocol.model.Block
import org.alephium.storage.BlockFlow.ChainIndex

object Network {
  private val config = ConfigFactory.load().getConfig("alephium")

  val port: Int               = config.getInt("port")
  val pingFrequency: Duration = config.getDuration("pingFrequency")
  val groups: Int             = config.getInt("groups")

  val peers: Seq[InetSocketAddress] = {
    val rawInput = config.getString("peers")
    if (rawInput.isEmpty) Seq.empty
    else {
      val addresses = rawInput.split(';')
      addresses.map { address =>
        val host = address.takeWhile(_ != ':')
        val port = address.dropWhile(_ != ':').tail.toInt
        new InetSocketAddress(host, port)
      }
    }
  }

  def createBlockFlow(groups: Int): Seq[Seq[Block]] = {
    Seq.tabulate(groups, groups) {
      case (from, to) => Miner.mineGenesis(ChainIndex(from, to))
    }
  }

  val blocksForFlow: Seq[Seq[Block]] = createBlockFlow(groups)
}
