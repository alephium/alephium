package org.alephium

import java.net.InetSocketAddress
import com.typesafe.scalalogging.StrictLogging

import org.alephium.client.Node
import org.alephium.constant.Network
import org.alephium.network.{TcpHandler, MessageHandler}

import scala.sys.process._

// scalastyle:off magic.number

trait Mode {
  def builders: TcpHandler.Builder with MessageHandler.Builder =
    new TcpHandler.Builder with MessageHandler.Builder

  def createNode(args: Array[String]): Node

  def getIndex(args: Array[String]): Int

  def index2Ip(index: Int): InetSocketAddress

  def getHttpPort(args: Array[String]): Int
}

object Mode {
  class Aws extends Mode with StrictLogging {
    override def createNode(args: Array[String]): Node = Node(builders, "Root", Network.port, Network.groups)

    override def getIndex(args: Array[String]): Int = {
      val hostname = "hostname".!!.stripLineEnd
      logger.info(hostname)
      hostname.split('-').last.toInt - 10
    }

    override def index2Ip(index: Int): InetSocketAddress = {
      val startIndex  = 10
      val peerIndex   = startIndex + index
      val peerAddress = s"10.0.0.$peerIndex"

      new InetSocketAddress(peerAddress, Network.port)
    }

    override def getHttpPort(args: Array[String]): Int = 8080
  }

  class Local extends Mode {
    override def createNode(args: Array[String]): Node = Node(builders, "Root", args(0).toInt, Network.groups)

    override def getIndex(args: Array[String]): Int = {
      val port = args(0).toInt
      port - Network.port
    }

    override def index2Ip(index: Int): InetSocketAddress = {
      new InetSocketAddress("localhost", Network.port + index)
    }

    override def getHttpPort(args: Array[String]): Int = 8080 + getIndex(args)
  }
}
