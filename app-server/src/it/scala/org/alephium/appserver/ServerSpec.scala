package org.alephium.appserver

import java.net.ServerSocket

import scala.concurrent.Promise

import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{ContentTypes, HttpEntity, HttpMethods, HttpRequest}
import akka.http.scaladsl.model.ws._
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream.scaladsl.{Flow, Keep, Sink, Source}
import akka.testkit.TestProbe
import io.circe.Decoder
import io.circe.parser.parse
import org.scalatest.EitherValues._
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.time.{Minutes, Span}

import org.alephium.appserver.RPCModel._
import org.alephium.flow.AlephiumFlowActorSpec
import org.alephium.flow.client.Node
import org.alephium.flow.io.RocksDBStorage.Settings
import org.alephium.flow.platform._
import org.alephium.rpc.model.JsonRPC
import org.alephium.util._

class ServerSpec extends AlephiumSpec {
  it should "work with 2 nodes" in new Fixture("2-nodes") {

    val boot0 = bootNode(publicPort = masterPort, brokerId = 0).start
    val boot1 = bootNode(publicPort = peerPort, brokerId   = 1).start
    boot0.futureValue is (())
    boot1.futureValue is (())

    val selfClique = request[SelfClique](rpcMasterPort, getSelfClique)
    val group      = request[Group](rpcMasterPort, getGroup(publicKey))
    val index      = group.group / selfClique.groupNumPerBroker
    val rpcPort    = selfClique.peers(index).rpcPort.get

    request[Balance](rpcPort, getBalance(publicKey)) is initialBalance

    startWS(wsMasterPort)

    request[TransferResult](rpcPort, transfer(publicKey, tranferKey, privateKey, transferAmount))

    selfClique.peers.foreach { peer =>
      request[Boolean](peer.rpcPort.get, startMining) is true
    }

    blockNotifyProbe.receiveN(30, Duration.unsafe(1000 * 120).asScala)

    selfClique.peers.foreach { peer =>
      request[Boolean](peer.rpcPort.get, stopMining) is true
    }

    request[Balance](rpcPort, getBalance(publicKey)) is Balance(
      initialBalance.balance - transferAmount,
      1)
  }

  class Fixture(name: String) extends AlephiumFlowActorSpec(name) with ScalaFutures {
    implicit override val patienceConfig = PatienceConfig(timeout = Span(1, Minutes))

    val publicKey  = "4b67a9704059abf76b5d75be94b0d16a85dd66d7dc106fcc2dd200bab0f45f77"
    val privateKey = "b0e218ff0d40482d37bb787dccc7a4c9a6d56c26885f66c6b5ce23c87c891f5e"
    val tranferKey = "4681f79b0225c208e1dee62fe05af3e02a58571a0b668ea5472f35da7acc2f13"

    val initialBalance = Balance(100, 1)
    val transferAmount = 10

    val masterPort    = new ServerSocket(0).getLocalPort
    val rpcMasterPort = masterPort + 1000
    val wsMasterPort  = masterPort + 2000
    def peerPort      = new ServerSocket(0).getLocalPort

    val blockNotifyProbe = TestProbe()

    def request[T: Decoder](port: Int, content: String): T = {
      val httpRequest =
        HttpRequest(HttpMethods.POST,
                    uri    = s"http://localhost:${port}",
                    entity = HttpEntity(ContentTypes.`application/json`, content))

      val response = Http().singleRequest(httpRequest).futureValue

      (for {
        json    <- parse(Unmarshal(response.entity).to[String].futureValue)
        request <- json.as[JsonRPC.Response.Success]
        t       <- request.result.as[T]
      } yield t).right.value
    }

    def bootNode(publicPort: Int, brokerId: Int): Server = {
      new Server(
        new Mode with PlatformConfigFixture {
          override val newPath = rootPath.resolveSibling(
            s"${rootPath.getFileName}-${this.getClass.getSimpleName}-${brokerId}")

          override val configValues = Map(
            ("alephium.network.masterAddress", s"localhost:$masterPort"),
            ("alephium.network.publicAddress", s"localhost:$publicPort"),
            ("alephium.network.rpcPort", publicPort + 1000),
            ("alephium.network.wsPort", publicPort + 2000),
            ("alephium.broker.brokerId", brokerId)
          )
          override implicit lazy val config =
            PlatformConfig.build(newConfig, newPath, Settings.writeOptions, None)
          val node: Node = Node.build(builders, s"node-$brokerId")(config)
        }
      )
    }

    def startWS(port: Int): Promise[Option[Message]] = {
      val flow: Flow[Message, Message, Promise[Option[Message]]] =
        Flow.fromSinkAndSourceMat(
          Sink.foreach[Message] { blockNotify =>
            blockNotifyProbe.ref ! blockNotify
          },
          Source.empty
            .concatMat(Source.maybe[Message])(Keep.right)
        )(Keep.right)

      val (_, promise) =
        Http()
          .singleWebSocketRequest(WebSocketRequest(s"ws://127.0.0.1:${port}/events"), flow)

      promise
    }

    def jsonRpc(method: String, params: String): String =
      s"""{"jsonrpc":"2.0","id": 0,"method":"$method","params": $params}"""

    val getSelfClique = jsonRpc("self_clique", "{}")

    def getGroup(address: String) = jsonRpc("get_group", s"""{"address":"$address"}""")

    def getBalance(address: String) =
      jsonRpc("get_balance", s"""{"address":"${address}","type":"pkh"}""")

    def transfer(from: String, to: String, fromPrivate: String, amount: Int) = jsonRpc(
      "transfer",
      s"""{"fromAddress":"$from","fromType":"pkh","toAddress":"$to","toType":"pkh","value":$amount,"fromPrivateKey":"$fromPrivate"}"""
    )

    val startMining = jsonRpc("mining_start", "{}")
    val stopMining  = jsonRpc("mining_stop", "{}")
  }
}
