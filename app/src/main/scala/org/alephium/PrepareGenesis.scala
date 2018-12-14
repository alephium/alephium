package org.alephium

import java.io.{File, FileWriter}

import com.typesafe.config.ConfigFactory
import com.typesafe.scalalogging.StrictLogging
import org.alephium.flow.client.Miner
import org.alephium.flow.constant.{Consensus, Network}
import org.alephium.flow.model.ChainIndex
import org.alephium.protocol.model.Block

import scala.collection.parallel.ParSeq

object PrepareGenesis extends App with StrictLogging {

  def createGenesisBlocks(groups: Int): ParSeq[Block] = {
    (0 until groups * groups).par.map { index =>
      val from = index / groups
      val to   = index % groups
      Miner.mineGenesis(ChainIndex(from, to))
    }
  }

  def run(): Unit = {
    val configFile = new File(args.head)
    val config     = ConfigFactory.parseFile(configFile)
    val path       = "alephium.nonces"

    logger.info(s"Leading zeros: #${Consensus.numZerosAtLeastInHash}")

    val start = System.currentTimeMillis()
    if (config.hasPath(path)) {
      logger.warn(s"Nonces have already been generated in the config file")
    } else {
      val genesis = createGenesisBlocks(Network.groups)
      val nonces  = genesis.map(_.blockHeader.nonce)
      val line    = s"\n$path = [${nonces.mkString(",")}]"

      val writer = new FileWriter(configFile, true)
      writer.append(line)
      writer.close()
    }
    val end = System.currentTimeMillis()
    logger.info(s"Elapsed: ${(end - start) / 1000}s")
  }

  run()
}
