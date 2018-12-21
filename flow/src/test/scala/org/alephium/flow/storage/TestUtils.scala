package org.alephium.flow.storage

import java.nio.file.{Files, Path}
import java.util.Comparator

import akka.actor.ActorSystem
import akka.testkit.TestProbe
import org.alephium.flow.PlatformConfig
import org.alephium.util.{Files => AFiles}
import org.alephium.protocol.model.ChainIndex

object TestUtils {

  def createBlockHandlersProbe(implicit config: PlatformConfig,
                               system: ActorSystem): AllHandlers = {
    val flowHandler = TestProbe().ref
    val blockHandlers = (for {
      from <- 0 until config.groups
      to   <- 0 until config.groups
      chainIndex = ChainIndex(from, to)
      if chainIndex.relateTo(config.mainGroup)
    } yield {
      chainIndex -> TestProbe().ref
    }).toMap
    val headerHandlers = (for {
      from <- 0 until config.groups
      to   <- 0 until config.groups
      chainIndex = ChainIndex(from, to)
      if !chainIndex.relateTo(config.mainGroup)
    } yield {
      chainIndex -> TestProbe().ref
    }).toMap
    AllHandlers(flowHandler, blockHandlers, headerHandlers)
  }

  def cleanup(path: Path): Unit = {
    if (path.startsWith(AFiles.tmpDir)) {
      Files.walk(path).sorted(Comparator.reverseOrder[Path]).forEach(p => Files.delete(p))
    }
  }
}
