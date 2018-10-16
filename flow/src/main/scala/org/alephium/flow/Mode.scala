package org.alephium.flow

import java.net.InetSocketAddress

import com.typesafe.scalalogging.StrictLogging
import org.alephium.flow.client.{Miner, Node}
import org.alephium.flow.network.TcpHandler

import scala.sys.process._

// scalastyle:off magic.number

trait Mode {
  final implicit val config: PlatformConfig = PlatformConfig.load()

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
    val port: Int = config.port

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

      new InetSocketAddress(peerAddress, config.port)
    }

    override def createNode: Node =
      Node(builders, "Root", config.port, config.groups)
  }

  class Local(val port: Int) extends Mode {
    val index: Int = {
      port - config.port
    }

    def httpPort: Int = 8080 + index

    override def index2Ip(index: Int): InetSocketAddress = {
      new InetSocketAddress("localhost", config.port + index)
    }

    override def createNode: Node =
      Node(builders, "Root", port, config.groups)
  }
}
