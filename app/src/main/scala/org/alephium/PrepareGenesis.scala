package org.alephium

import java.io.FileWriter

import com.typesafe.scalalogging.StrictLogging
import org.alephium.flow.PlatformConfig
import org.alephium.flow.client.Miner
import org.alephium.protocol.model.{Block, ChainIndex}

import scala.collection.parallel.ParSeq

object PrepareGenesis extends App with StrictLogging with PlatformConfig.Default {
  def createGenesisBlocks(groups: Int): ParSeq[Block] = {
    (0 until groups * groups).par.map { index =>
      val from = index / groups
      val to   = index % groups
      Miner.mineGenesis(ChainIndex(from, to))
    }
  }

  def run(): Unit = {
    val path = "nonces"

    logger.info(s"Leading zeros: #${config.numZerosAtLeastInHash}")

    val start = System.currentTimeMillis()
    if (config.underlying.hasPath(path)) {
      logger.warn(s"Nonces have already been generated in the config file")
    } else {
      val genesis = createGenesisBlocks(config.groups)
      val nonces  = genesis.map(_.blockHeader.nonce)
      val line    = s"alephium.$path = [${nonces.mkString(",")}]"

      val noncesPath = PlatformConfig.getNoncesFilePath(config.groups)
      val writer     = new FileWriter(noncesPath.toFile, true)
      writer.append(line)
      writer.close()
    }
    val end = System.currentTimeMillis()
    logger.info(s"Elapsed: ${(end - start) / 1000}s")
  }

  run()
}
