package org.alephium

import java.io.FileWriter

import com.typesafe.scalalogging.StrictLogging
import org.alephium.flow.PlatformConfig
import org.alephium.flow.client.Miner
import org.alephium.protocol.model.{Block, ChainIndex}

import scala.collection.parallel.ParSeq

object PrepareGenesis extends App with StrictLogging {
  implicit val config = PlatformConfig.Default

  def createGenesisBlocks(groups: Int): ParSeq[Block] = {
    (0 until groups * groups).par.map { index =>
      val from = index / groups
      val to   = index % groups
      Miner.mineGenesis(ChainIndex(from, to))
    }
  }

  def run(): Unit = {
    val path = "nonces"

    logger.info(s"Groups: ${config.groups}; Leading zeros: #${config.numZerosAtLeastInHash}")

    val start = System.currentTimeMillis()

    val genesis = createGenesisBlocks(config.groups)
    val nonces  = genesis.map(_.header.nonce)
    val line    = s"$path = [${nonces.mkString(",")}]"
    val noncesPath =
      PlatformConfig.Default.getNoncesPath(config.groups, config.numZerosAtLeastInHash)
    val writer = new FileWriter(noncesPath.toFile)
    writer.append(line)
    writer.close()

    val end = System.currentTimeMillis()
    logger.info(s"Elapsed: ${(end - start) / 1000}s")
  }

  run()
}
