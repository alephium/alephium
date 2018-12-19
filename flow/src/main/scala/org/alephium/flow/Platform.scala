package org.alephium.flow

import java.time.Instant

import scala.concurrent.Future
import com.typesafe.scalalogging.StrictLogging
import org.alephium.flow.client.Node
import org.alephium.flow.network.PeerManager

trait Platform extends App with StrictLogging {
  def mode: Mode

  def init(): Future[Unit] = {
    val node = mode.createNode
    runServer(node)
  }

  def connect(node: Node, index: Int): Unit = {
    for (peer <- 0 until mode.config.groups if peer != index && peer < index) {
      val remote = mode.index2Ip(peer)
      val until  = Instant.now().plusMillis(mode.config.retryTimeout.toMillis)
      node.peerManager ! PeerManager.Connect(remote, until)
    }
  }

  def runServer(node: Node): Future[Unit]
}
