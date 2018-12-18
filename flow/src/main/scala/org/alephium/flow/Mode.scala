package org.alephium.flow

import java.net.InetSocketAddress

import com.typesafe.scalalogging.StrictLogging
import org.alephium.flow.client.{Miner, Node}
import org.alephium.flow.network.TcpHandler
import org.alephium.protocol.model.GroupIndex

import scala.sys.process._

// scalastyle:off magic.number

trait Mode extends PlatformConfig.Default {
  def port: Int

  def index: Int

  def mainGroup: GroupIndex = {
    // Double check if index is matched with mainGroup
    val groupIndex = GroupIndex(math.abs(index) % config.groups)
    assert(groupIndex == config.mainGroup)
    groupIndex
  }

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
      Node(builders, "Root")
  }

  class Local extends Mode {
    val port: Int = config.port

    val index: Int = {
      port - 9973
    }

    def httpPort: Int = 8080 + index

    override def index2Ip(index: Int): InetSocketAddress = {
      new InetSocketAddress("localhost", config.port + index)
    }

    override def createNode: Node =
      Node(builders, "Root")
  }
}
