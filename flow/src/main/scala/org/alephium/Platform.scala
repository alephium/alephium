package org.alephium

import akka.http.scaladsl.Http
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Directives._
import akka.stream.ActorMaterializer
import com.typesafe.scalalogging.StrictLogging
import org.alephium.client.{Miner, Node}
import org.alephium.constant.Network
import org.alephium.crypto.ED25519PublicKey
import org.alephium.mock.MockMiner
import org.alephium.network.PeerManager
import org.alephium.storage.BlockFlow.ChainIndex
import org.alephium.util.Hex._

import scala.concurrent.Future

// scalastyle:off magic.number
trait Platform extends App with StrictLogging {
  def mode: Mode

  def init(): Future[Http.ServerBinding] = {
    val node  = mode.createNode
    val index = mode.index

    connect(node, index)
    runServer(node, index)
  }

  def connect(node: Node, index: Int): Unit = {
    Thread.sleep(1000 * 60)

    if (index > 0) {
      val parentIndex = index / 2
      val remote      = mode.index2Ip(parentIndex)
      node.peerManager ! PeerManager.Connect(remote)
    }
  }

  def runServer(node: Node, index: Int): Future[Http.ServerBinding] = {
    implicit val system           = node.system
    implicit val materializer     = ActorMaterializer()
    implicit val executionContext = system.dispatcher

    val groups = Network.groups
    val second = index / groups
    val from   = index % groups

    logger.info(s"second: $second, index: $from")

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

    Http().bindAndHandle(route, "0.0.0.0", mode.httpPort)
  }
}
