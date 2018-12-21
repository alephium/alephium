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
    val elapsedTime = System.currentTimeMillis() - header.timestamp
    log.info(s"New block/header: ${header.shortHex}; elapsed: ${elapsedTime}ms")
  }
}
