package org.alephium

import java.net.InetSocketAddress

import akka.pattern.ask
import akka.stream.ActorMaterializer
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.ws.{Message, TextMessage}
import akka.http.scaladsl.server.Directives._
import akka.stream.scaladsl.Flow
import akka.util.Timeout
import org.alephium.client.{Miner, Node}
import org.alephium.constant.Network
import org.alephium.crypto.ED25519PublicKey
import org.alephium.network.PeerManager
import org.alephium.storage.{BlockFlow, BlockHandler}
import org.alephium.util.Hex._

import scala.concurrent.duration._
import org.alephium.storage.BlockFlow.{BlockInfo, ChainIndex}

import scala.concurrent.Future

// scalastyle:off magic.number
object ServerFun extends App {
  val node = Node("ServerFun", Network.port)

  run()

  def toJson(blockInfo: BlockInfo): String = {
    val chainIndex = blockInfo.chainIndex
    s"""{"timestamp":${blockInfo.timestamp},"chainFrom":${chainIndex.from},"chainTo":${chainIndex.to}}"""
  }

  def run(): Unit = {
    if (Network.port == 9973) runServer()

    val index      = Network.port - 9973
    val chainIndex = ChainIndex(index / 2, index % 2)

    Thread.sleep(1000 * 20)

    val peerPort = (index + 1) % 4 + 9973
    val remote   = new InetSocketAddress("localhost", peerPort)
    node.peerManager ! PeerManager.Connect(remote)

    Thread.sleep(1000 * 20)

    val publicKey: ED25519PublicKey = ED25519PublicKey.unsafeFrom(
      hex"2db399c90fee96ec2310b62e3f62b5bd87972a96e5fa64675f0adc683546cd1d")

    val miner = node.system.actorOf(Miner.props(publicKey, node, chainIndex))
    miner ! Miner.Start
  }

  def runServer(): Future[Http.ServerBinding] = {
    implicit val system           = node.system
    implicit val materializer     = ActorMaterializer()
    implicit val executionContext = system.dispatcher
    implicit val timeout          = Timeout(5.seconds)

    val wsService =
      Flow[Message].mapAsync(5) { _ =>
        val blockInfosF = (node.blockHandler ? BlockHandler.GetBlockInfo)
          .mapTo[Seq[BlockFlow.BlockInfo]]
        val messageF = blockInfosF.map { blockInfos =>
          "[" + blockInfos.map(toJson).mkString(",") + "]"
        }
        messageF.map(TextMessage.apply)
      }

    val route = path("blocks") {
      get {
        handleWebSocketMessages(wsService)
      }
    }

    Http().bindAndHandle(route, "localhost", 8080)
  }
}
