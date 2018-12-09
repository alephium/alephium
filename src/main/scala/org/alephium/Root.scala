package org.alephium

import java.net.InetSocketAddress

import akka.http.scaladsl.Http
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Directives._
import akka.stream.ActorMaterializer
import org.alephium.client.{Miner, Node}
import org.alephium.constant.Network
import org.alephium.crypto.ED25519PublicKey
import org.alephium.mock.MockMiner
import org.alephium.network.PeerManager
import org.alephium.storage.BlockFlow.ChainIndex
import org.alephium.util.Hex._

import scala.concurrent.Future
import scala.sys.process._

trait Mode {
  def createNode(args: Array[String]): Node

  def getIndex(args: Array[String]): Int

  def index2Ip(index: Int): InetSocketAddress

  def getHttpPort(args: Array[String]): Int
}

object Aws extends Mode {
  override def createNode(args: Array[String]): Node = Node("Root", Network.port, Network.groups)

  override def getIndex(args: Array[String]): Int = {
    val hostname = "hostname".!!.stripLineEnd
    println(hostname)
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

object Local extends Mode {
  override def createNode(args: Array[String]): Node = Node("Root", args(0).toInt, Network.groups)

  override def getIndex(args: Array[String]): Int = {
    val port = args(0).toInt
    port - Network.port
  }

  override def index2Ip(index: Int): InetSocketAddress = {
    new InetSocketAddress("localhost", Network.port + index)
  }

  override def getHttpPort(args: Array[String]): Int = 8080 + getIndex(args)
}

// scalastyle:off magic.number
object Root extends App {
  val mode   = Aws
  val node   = mode.createNode(args)
  val index  = mode.getIndex(args)
  val groups = Network.groups

  val second = index / groups
  val from   = index % groups
  println(s"second: $second, index: $from")

  connect()
  runServer()

  def connect(): Unit = {
    Thread.sleep(1000 * 60)

    if (index > 0) {
      val parentIndex = index / 2
      val remote      = mode.index2Ip(parentIndex)
      node.peerManager ! PeerManager.Connect(remote)
    }
  }

  def runServer(): Future[Http.ServerBinding] = {
    implicit val system           = node.system
    implicit val materializer     = ActorMaterializer()
    implicit val executionContext = system.dispatcher

    val route = path("mining") {
      put {
        val publicKey: ED25519PublicKey = ED25519PublicKey.unsafeFrom(
          hex"2db399c90fee96ec2310b62e3f62b5bd87972a96e5fa64675f0adc683546cd1d")

        (0 until groups).foreach { to =>
          val chainIndex = ChainIndex(from, to)
          val miner      = node.system.actorOf(MockMiner.props(publicKey, node, chainIndex, second == 0))
          miner ! Miner.Start
        }

        complete((StatusCodes.Accepted, "Start mining"))
      }
    }

    Http().bindAndHandle(route, "0.0.0.0", mode.getHttpPort(args))
  }
}
