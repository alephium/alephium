package org.alephium.flow.storage

import akka.event.LoggingAdapter
import org.alephium.flow.PlatformConfig
import org.alephium.protocol.model.{BlockHeader, ChainIndex}

trait ChainHandlerLogger {
  def blockFlow: BlockFlow
  def chainIndex: ChainIndex
  implicit def config: PlatformConfig
  def log: LoggingAdapter
  def chain: BlockHashPool

  def logInfo(header: BlockHeader): Unit = {
    val total       = blockFlow.numHashes - config.chainNum // exclude genesis blocks
    val elapsedTime = System.currentTimeMillis() - header.timestamp
    val heights = for {
      i <- 0 until config.groups
      j <- 0 until config.groups
      height = blockFlow.getHashChain(ChainIndex(i, j)).maxHeight
    } yield s"($i, $j, $height)"
    val heightsInfo = heights.mkString(", ")
    log.info(
      s"$chainIndex; total: $total; ${chain.show(header.hash)}; elapsed: ${elapsedTime}ms, heights: $heightsInfo")
  }
}
