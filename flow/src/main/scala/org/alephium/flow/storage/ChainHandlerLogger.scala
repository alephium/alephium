package org.alephium.flow.storage

import akka.event.LoggingAdapter
import org.alephium.protocol.model.BlockHeader

trait ChainHandlerLogger {
  def log: LoggingAdapter

  def logInfo(header: BlockHeader): Unit = {
    val elapsedTime = System.currentTimeMillis() - header.timestamp
    log.info(s"Potentially new block/header: ${header.shortHex}; elapsed: ${elapsedTime}ms")
  }
}
