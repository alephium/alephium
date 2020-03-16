package org.alephium.flow.core

import java.nio.file.Path

import akka.actor.ActorSystem
import akka.testkit.TestProbe

import org.alephium.flow.io.IOUtils
import org.alephium.flow.platform.PlatformConfig
import org.alephium.protocol.model.ChainIndex
import org.alephium.util.{ActorRefT, Files => AFiles}

object TestUtils {

  def createBlockHandlersProbe(implicit config: PlatformConfig,
                               system: ActorSystem): AllHandlers = {
    val flowHandler = ActorRefT[FlowHandler.Command](TestProbe().ref)
    val txHandler   = ActorRefT[TxHandler.Command](TestProbe().ref)
    val blockHandlers = (for {
      from <- 0 until config.groups
      to   <- 0 until config.groups
      chainIndex = ChainIndex.unsafe(from, to)
      if chainIndex.relateTo(config.brokerInfo)
    } yield {
      chainIndex -> ActorRefT[BlockChainHandler.Command](TestProbe().ref)
    }).toMap
    val headerHandlers = (for {
      from <- 0 until config.groups
      to   <- 0 until config.groups
      chainIndex = ChainIndex.unsafe(from, to)
      if !chainIndex.relateTo(config.brokerInfo)
    } yield {
      chainIndex -> ActorRefT[HeaderChainHandler.Command](TestProbe().ref)
    }).toMap
    AllHandlers(flowHandler, txHandler, blockHandlers, headerHandlers)
  }

  // remove all the content under the path; the path itself would be kept
  def clear(path: Path): Unit = {
    if (path.startsWith(AFiles.tmpDir)) {
      IOUtils.clearUnsafe(path)
    } else throw new RuntimeException("Only files under tmp dir could be cleared")
  }
}
