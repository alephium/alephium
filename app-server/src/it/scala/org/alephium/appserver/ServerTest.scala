package org.alephium.appserver

import java.net.InetSocketAddress

import scala.annotation.tailrec
import scala.concurrent.{ExecutionContext, Future, Promise}

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
import org.scalatest.concurrent.{Eventually, ScalaFutures}
import org.scalatest.time.{Minutes, Span}

import org.alephium.appserver.RPCModel._
import org.alephium.crypto.{ED25519, ED25519PrivateKey, ED25519Signature}
import org.alephium.flow.AlephiumFlowActorSpec
import org.alephium.flow.client.Node
import org.alephium.flow.io.StoragesFixture
import org.alephium.flow.platform._
import org.alephium.rpc.model.JsonRPC
import org.alephium.rpc.model.JsonRPC.NotificationUnsafe
import org.alephium.util._

class ServerTest extends AlephiumSpec {
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

    val fromTs = TimeStamp.now()

    val server0 = bootNode(publicPort = masterPort, brokerId = 0)
    server0.start.futureValue is (())

    request[Boolean](rpcMasterPort, getSelfCliqueSynced) is false

    val server1 = bootNode(publicPort = peerPort, brokerId = 1)
    server1.start.futureValue is (())

    eventually(request[Boolean](rpcMasterPort, getSelfCliqueSynced) is true)

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
    awaitNewBlock(tx.fromGroup, tx.fromGroup)

    request[Balance](rpcPort, getBalance(publicKey)) is
      Balance(initialBalance.balance - transferAmount, 1)

    val createTx =
      request[CreateTransactionResult](rpcPort,
                                       createTransaction(publicKey, tranferKey, transferAmount))

    val tx2 =
      request[TransferResult](rpcPort, sendTransaction(createTx))

    awaitNewBlock(tx2.fromGroup, tx2.toGroup)
    awaitNewBlock(tx2.fromGroup, tx2.fromGroup)

    selfClique.peers.foreach { peer =>
      request[Boolean](peer.rpcPort.get, stopMining) is true
    }

    request[Balance](rpcPort, getBalance(publicKey)) is
      Balance(initialBalance.balance - (2 * transferAmount), 1)

    val toTs = TimeStamp.now()

    //TODO Find a better assertion
    request[FetchResponse](rpcPort, blockflowFetch(fromTs, toTs)).blocks.size should be > 16

    server1.stop()
    server0.stop()
  }

  class Fixture(name: String)
      extends AlephiumFlowActorSpec(name)
      with ScalaFutures
      with Eventually {
    override implicit val patienceConfig = PatienceConfig(timeout = Span(1, Minutes))

    val publicKey  = "9f93bf7f1211f510e3d9c4fc7fb933f94830ba83190da62dbfc9baa8b0d36276"
    val privateKey = "a2e3e382cb262ee8af95069f6edfaa4685fc1294805336bafac206c1f115aa96"
    val tranferKey = ED25519.generatePriPub()._2.toHexString

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

    @tailrec
    final def awaitNewBlock(from: Int, to: Int): Unit = {
      val timeout = Duration.ofMinutesUnsafe(2).asScala
      blockNotifyProbe.receiveOne(max = timeout) match {
        case TextMessage.Strict(text) =>
          val json         = parse(text).right.value
          val notification = json.as[NotificationUnsafe].right.value.asNotification.right.value
          val blockEntry   = notification.params.as[BlockEntry].right.value
          if ((blockEntry.chainFrom equals from) && (blockEntry.chainTo equals to)) ()
          else awaitNewBlock(from, to)
      }
    }

    def bootNode(publicPort: Int, brokerId: Int): Server = {
      new Server(
        new Mode with PlatformConfigFixture with StoragesFixture {
          override val configValues = Map(
            ("alephium.network.masterAddress", s"localhost:$masterPort"),
            ("alephium.network.publicAddress", s"localhost:$publicPort"),
            ("alephium.network.rpcPort", publicPort - 100),
            ("alephium.network.wsPort", publicPort - 200),
            ("alephium.broker.brokerId", brokerId)
          )
          override implicit lazy val config =
            PlatformConfig.build(newConfig, rootPath, None)
          val node: Node = Node.build(builders, s"node-$brokerId", storages)(config)

          implicit val executionContext: ExecutionContext = node.system.dispatcher
          override def shutdown(): Future[Unit] =
            for {
              _ <- node.shutdown()
              _ <- Future.successful(storages.dESTROY())
            } yield ()
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

    val getSelfCliqueSynced = jsonRpc("self_clique_synced", "{}")

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

    def blockflowFetch(fromTs: TimeStamp, toTs: TimeStamp) =
      jsonRpc("blockflow_fetch", s"""{"fromTs":${fromTs.millis},"toTs":${toTs.millis}}""")
  }
}
