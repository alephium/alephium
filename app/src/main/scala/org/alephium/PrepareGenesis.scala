package org.alephium

import java.io.{File, FileWriter}

import com.typesafe.config.ConfigFactory
import com.typesafe.scalalogging.StrictLogging
import org.alephium.flow.client.Miner
import org.alephium.flow.constant.Network
import org.alephium.flow.model.ChainIndex
import org.alephium.protocol.model.Block

object PrepareGenesis extends App with StrictLogging {
  def createGenesisBlocks(groups: Int): Seq[Seq[Block]] = {
    Seq.tabulate(groups, groups) {
      case (from, to) => Miner.mineGenesis(ChainIndex(from, to))
    }
  }

  def run(): Unit = {
    val configFile = new File(args.head)
    val config     = ConfigFactory.parseFile(configFile)
    val path       = "alephium.nonces"

    if (config.hasPath(path)) {
      logger.warn(s"Nonces have already been generated in the config file")
    } else {
      val genesis = createGenesisBlocks(Network.groups).flatten
      val nonces  = genesis.map(_.blockHeader.nonce)
      val line    = s"\n$path = [${nonces.mkString(",")}]"

      val writer = new FileWriter(configFile, true)
      writer.append(line)
      writer.close()
    }
  }

  run()
}
