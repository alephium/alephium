package org.alephium.flow.core

import java.nio.file.Path

import akka.actor.ActorSystem
import akka.testkit.TestProbe

import org.alephium.flow.platform.PlatformConfig
import org.alephium.io.IOUtils
import org.alephium.protocol.model.ChainIndex
import org.alephium.util.{ActorRefT, Files => AFiles}

object TestUtils {
  case class AllHandlerProbs(flowHandler: TestProbe,
                             txHandler: TestProbe,
                             blockHandlers: Map[ChainIndex, TestProbe],
                             headerHandlers: Map[ChainIndex, TestProbe])

  def createBlockHandlersProbe(implicit config: PlatformConfig,
                               system: ActorSystem): (AllHandlers, AllHandlerProbs) = {
    val flowProbe   = TestProbe()
    val flowHandler = ActorRefT[FlowHandler.Command](flowProbe.ref)
    val txProbe     = TestProbe()
    val txHandler   = ActorRefT[TxHandler.Command](txProbe.ref)
    val blockHandlers = (for {
      from <- 0 until config.groups
      to   <- 0 until config.groups
      chainIndex = ChainIndex.unsafe(from, to)
      if chainIndex.relateTo(config.brokerInfo)
    } yield {
      val probe = TestProbe()
      chainIndex -> (ActorRefT[BlockChainHandler.Command](probe.ref) -> probe)
    }).toMap
    val headerHandlers = (for {
      from <- 0 until config.groups
      to   <- 0 until config.groups
      chainIndex = ChainIndex.unsafe(from, to)
      if !chainIndex.relateTo(config.brokerInfo)
    } yield {
      val probe = TestProbe()
      chainIndex -> (ActorRefT[HeaderChainHandler.Command](probe.ref) -> probe)
    }).toMap
    val allHandlers = AllHandlers(flowHandler,
                                  txHandler,
                                  blockHandlers.view.mapValues(_._1).toMap,
                                  headerHandlers.view.mapValues(_._1).toMap)
    val allProbes = AllHandlerProbs(flowProbe,
                                    txProbe,
                                    blockHandlers.view.mapValues(_._2).toMap,
                                    headerHandlers.view.mapValues(_._2).toMap)
    allHandlers -> allProbes
  }

  // remove all the content under the path; the path itself would be kept
  def clear(path: Path): Unit = {
    if (path.startsWith(AFiles.tmpDir)) {
      IOUtils.clearUnsafe(path)
    } else throw new RuntimeException("Only files under tmp dir could be cleared")
  }
}
