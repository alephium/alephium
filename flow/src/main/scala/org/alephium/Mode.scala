package org.alephium

import java.net.InetSocketAddress

import com.typesafe.scalalogging.StrictLogging
import org.alephium.client.{Miner, Node}
import org.alephium.constant.Network
import org.alephium.network.TcpHandler

import scala.sys.process._

// scalastyle:off magic.number

trait Mode {
  def port: Int

  def index: Int

  def httpPort: Int

  def index2Ip(index: Int): InetSocketAddress

  def builders: Mode.Builder = Mode.defaultBuilders

  def createNode: Node
}

object Mode {

  type Builder = TcpHandler.Builder with Miner.Builder

  def defaultBuilders: Builder = new TcpHandler.Builder with Miner.Builder

  class Aws extends Mode with StrictLogging {
    val port: Int = Network.port

    val index: Int = {
      val hostname = "hostname".!!.stripLineEnd
      logger.info(hostname)
      hostname.split('-').last.toInt - 10
    }

    val httpPort: Int = 8080

    override def index2Ip(index: Int): InetSocketAddress = {
      val startIndex  = 10
      val peerIndex   = startIndex + index
      val peerAddress = s"10.0.0.$peerIndex"

      new InetSocketAddress(peerAddress, Network.port)
    }

    override def createNode: Node =
      Node(builders, "Root", Network.port, Network.groups)
  }

  class Local(val port: Int) extends Mode {
    val index: Int = {
      port - Network.port
    }

    def httpPort: Int = 8080 + index

    override def index2Ip(index: Int): InetSocketAddress = {
      new InetSocketAddress("localhost", Network.port + index)
    }

    override def createNode: Node =
      Node(builders, "Root", port, Network.groups)
  }
}
