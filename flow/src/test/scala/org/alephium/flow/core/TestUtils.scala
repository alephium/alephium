package org.alephium.flow.core

import java.nio.file.Path

import akka.actor.ActorSystem
import akka.testkit.TestProbe

import org.alephium.flow.io.Disk
import org.alephium.flow.platform.PlatformProfile
import org.alephium.protocol.model.ChainIndex
import org.alephium.util.{Files => AFiles}

object TestUtils {

  def createBlockHandlersProbe(implicit config: PlatformProfile,
                               system: ActorSystem): AllHandlers = {
    val flowHandler = TestProbe().ref
    val blockHandlers = (for {
      from <- 0 until config.groups
      to   <- 0 until config.groups
      chainIndex = ChainIndex(from, to)
      if chainIndex.relateTo(config.brokerInfo)
    } yield {
      chainIndex -> TestProbe().ref
    }).toMap
    val headerHandlers = (for {
      from <- 0 until config.groups
      to   <- 0 until config.groups
      chainIndex = ChainIndex(from, to)
      if !chainIndex.relateTo(config.brokerInfo)
    } yield {
      chainIndex -> TestProbe().ref
    }).toMap
    AllHandlers(flowHandler, blockHandlers, headerHandlers)
  }

  // remove all the content under the path; the path itself would be kept
  def clear(path: Path): Unit = {
    if (path.startsWith(AFiles.tmpDir)) {
      Disk.clearUnsafe(path)
    } else throw new RuntimeException("Only files under tmp dir could be cleared")
  }
}
