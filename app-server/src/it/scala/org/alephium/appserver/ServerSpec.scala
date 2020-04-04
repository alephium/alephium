package org.alephium.appserver

import java.net.InetSocketAddress

import scala.concurrent.Promise

import akka.actor.Terminated
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{ContentTypes, HttpEntity, HttpMethods, HttpRequest}
import akka.http.scaladsl.model.ws._
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.io.{IO, Tcp}
import akka.stream.scaladsl.{Flow, Keep, Sink, Source}
import akka.testkit.{SocketUtil, TestProbe}
import io.circe.Decoder
import io.circe.parser.parse
import org.scalatest.EitherValues._
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.time.{Minutes, Span}

import org.alephium.appserver.RPCModel._
import org.alephium.crypto.{ED25519, ED25519PrivateKey, ED25519Signature}
import org.alephium.flow.AlephiumFlowActorSpec
import org.alephium.flow.client.Node
import org.alephium.flow.io.RocksDBSource.Settings
import org.alephium.flow.platform._
import org.alephium.rpc.model.JsonRPC
import org.alephium.rpc.model.JsonRPC.NotificationUnsafe
import org.alephium.util._

class ServerSpec extends AlephiumSpec {
  it should "shutdown the node when Tcp port is used" in new Fixture("1-node") {
    val connection = TestProbe()
    IO(Tcp) ! Tcp.Bind(connection.ref, new InetSocketAddress(masterPort))

    val server = bootNode(publicPort = masterPort, brokerId = 0)
    server.mode.node.system.whenTerminated.futureValue is a[Terminated]
  }

  it should "shutdown the clique when one node of the clique is down" in new Fixture("2-nodes") {

    val server0 = bootNode(publicPort = masterPort, brokerId = 0)
    val server1 = bootNode(publicPort = peerPort, brokerId   = 1)
    Seq(server0.start(), server1.start()).foreach(_.futureValue is (()))

    server0.stop().futureValue is (())
    server1.mode.node.system.whenTerminated.futureValue is a[Terminated]
  }

  it should "work with 2 nodes" in new Fixture("2-nodes") {

    val server0 = bootNode(publicPort = masterPort, brokerId = 0)
    val server1 = bootNode(publicPort = peerPort, brokerId   = 1)
    Seq(server0.start, server1.start).foreach(_.futureValue is (()))

    val selfClique = request[SelfClique](rpcMasterPort, getSelfClique)
    val group      = request[Group](rpcMasterPort, getGroup(publicKey))
    val index      = group.group / selfClique.groupNumPerBroker
    val rpcPort    = selfClique.peers(index).rpcPort.get

    request[Balance](rpcPort, getBalance(publicKey)) is initialBalance

    startWS(wsMasterPort)

    val tx =
      request[TransferResult](rpcPort, transfer(publicKey, tranferKey, privateKey, transferAmount))

    selfClique.peers.foreach { peer =>
      request[Boolean](peer.rpcPort.get, startMining) is true
    }

    awaitNewBlock(tx.fromGroup, tx.toGroup)

    request[Balance](rpcPort, getBalance(publicKey)) is
      Balance(initialBalance.balance - transferAmount, 1)

    val createTx =
      request[CreateTransactionResult](rpcPort,
                                       createTransaction(publicKey, tranferKey, transferAmount))

    val tx2 =
      request[TransferResult](rpcPort, sendTransaction(createTx))

    awaitNewBlock(tx2.fromGroup, tx2.toGroup)

    selfClique.peers.foreach { peer =>
      request[Boolean](peer.rpcPort.get, stopMining) is true
    }

    request[Balance](rpcPort, getBalance(publicKey)) is
      Balance(initialBalance.balance - (2 * transferAmount), 1)
  }

  class Fixture(name: String) extends AlephiumFlowActorSpec(name) with ScalaFutures {
    implicit override val patienceConfig = PatienceConfig(timeout = Span(1, Minutes))

    val publicKey  = "4b67a9704059abf76b5d75be94b0d16a85dd66d7dc106fcc2dd200bab0f45f77"
    val privateKey = "b0e218ff0d40482d37bb787dccc7a4c9a6d56c26885f66c6b5ce23c87c891f5e"
    val tranferKey = "4681f79b0225c208e1dee62fe05af3e02a58571a0b668ea5472f35da7acc2f13"

    val initialBalance = Balance(100, 1)
    val transferAmount = 10

    val masterPort    = SocketUtil.temporaryLocalPort(SocketUtil.Both)
    val rpcMasterPort = masterPort - 100
    val wsMasterPort  = masterPort - 200
    def peerPort      = SocketUtil.temporaryLocalPort(SocketUtil.Both)

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

    def awaitNewBlock(from: Int, to: Int): Seq[Unit] = {
      var count   = 0
      val timeout = Duration.ofSecondsUnsafe(120).asScala
      blockNotifyProbe.receiveWhile(max = timeout) {
        case TextMessage.Strict(text) if count < 2 =>
          val json         = parse(text).right.value
          val notification = json.as[NotificationUnsafe].right.value.asNotification.right.value
          val blockEntry   = notification.params.as[BlockEntry].right.value
          if ((blockEntry.chainFrom equals from) && (blockEntry.chainTo equals to))
            count += 1
      }
    }

    def bootNode(publicPort: Int, brokerId: Int): Server = {
      new Server(
        new Mode with PlatformConfigFixture {
          override val configValues = Map(
            ("alephium.network.masterAddress", s"localhost:$masterPort"),
            ("alephium.network.publicAddress", s"localhost:$publicPort"),
            ("alephium.network.rpcPort", publicPort - 100),
            ("alephium.network.wsPort", publicPort - 200),
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

    def createTransaction(from: String, to: String, amount: Int) = jsonRpc(
      "create_transaction",
      s"""{"fromAddress":"$from","fromType":"pkh","toAddress":"$to","toType":"pkh","value":$amount}"""
    )

    def sendTransaction(createTransactionResult: CreateTransactionResult) = {
      val signature: ED25519Signature = ED25519.sign(
        Hex.unsafe(createTransactionResult.hash),
        ED25519PrivateKey.unsafe(Hex.unsafe(privateKey)))
      jsonRpc(
        "send_transaction",
        s"""{"tx":"${createTransactionResult.unsignedTx}","signature":"${signature.toHexString}","publicKey":"$publicKey"}"""
      )
    }

    val startMining = jsonRpc("mining_start", "{}")
    val stopMining  = jsonRpc("mining_stop", "{}")
  }
}
