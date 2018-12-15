package org.alephium.flow

import java.time.Instant

import akka.http.scaladsl.Http
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Directives.{complete, path, put}
import akka.stream.ActorMaterializer
import com.typesafe.scalalogging.StrictLogging
import org.alephium.crypto.ED25519PublicKey
import org.alephium.flow.client.{Miner, Node}
import org.alephium.flow.network.PeerManager
import org.alephium.protocol.model.ChainIndex
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
    if (index > 0) {
      val parentIndex = index / 2
      val remote      = mode.index2Ip(parentIndex)
      val until       = Instant.now().plusMillis(mode.config.retryTimeout.toMillis)
      node.peerManager ! PeerManager.Connect(remote, until)
    }
  }

  def runServer(node: Node, index: Int): Future[Http.ServerBinding] = {
    implicit val system           = node.system
    implicit val materializer     = ActorMaterializer()
    implicit val executionContext = system.dispatcher
    implicit val config           = mode.config

    val groups = mode.config.groups
    val from   = index % groups

    logger.info(s"index: $from")

    val route = path("mining") {
      put {
        val publicKey: ED25519PublicKey = ED25519PublicKey.unsafeFrom(
          hex"2db399c90fee96ec2310b62e3f62b5bd87972a96e5fa64675f0adc683546cd1d")

        (0 until groups).foreach { to =>
          val chainIndex = ChainIndex(from, to)
          val props = mode.builders
            .createMiner(publicKey, node, chainIndex)
            .withDispatcher("akka.actor.mining-dispatcher")
          val miner = node.system.actorOf(props, s"MockMiner-$from-$to")
          miner ! Miner.Start
        }

        complete((StatusCodes.Accepted, "Start mining"))
      }
    }

    Http().bindAndHandle(route, "0.0.0.0", mode.httpPort)
  }
}
