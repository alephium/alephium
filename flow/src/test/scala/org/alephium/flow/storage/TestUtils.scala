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
      if chainIndex.relateTo(config.brokerId)
    } yield {
      chainIndex -> TestProbe().ref
    }).toMap
    val headerHandlers = (for {
      from <- 0 until config.groups
      to   <- 0 until config.groups
      chainIndex = ChainIndex(from, to)
      if !chainIndex.relateTo(config.brokerId)
    } yield {
      chainIndex -> TestProbe().ref
    }).toMap
    AllHandlers(flowHandler, blockHandlers, headerHandlers)
  }

  // remove all the content under the path; the path itself would be kept
  def clear(path: Path): Unit = {
    if (path.startsWith(AFiles.tmpDir)) {
      val subFiles = Files.walk(path).sorted(Comparator.reverseOrder[Path]).toArray.init
      subFiles.foreach(p => Files.delete(p.asInstanceOf[Path]))
    } else throw new RuntimeException("Only files under tmp dir could be cleared")
  }
}
