package org.alephium.flow.core

import akka.event.LoggingAdapter

import org.alephium.protocol.model.BlockHeader
import org.alephium.util.TimeStamp

trait ChainHandlerLogger {
  def log: LoggingAdapter

  def logInfo(header: BlockHeader): Unit = {
    val elapsedTime = TimeStamp.now().diff(header.timestamp)
    log.info(s"Potentially new block/header: ${header.shortHex}; elapsed: $elapsedTime")
  }
}
