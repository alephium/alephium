package org.alephium.appserver

import java.net.InetSocketAddress

import scala.annotation.tailrec
import scala.concurrent.{Await, ExecutionContext, Future, Promise}

import akka.actor.{ActorSystem, Terminated}
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{ContentTypes, HttpEntity, HttpMethods, HttpRequest}
import akka.http.scaladsl.model.ws._
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.io.{IO, Tcp}
import akka.stream.scaladsl.{Flow, Keep, Sink, Source}
import akka.testkit.{SocketUtil, TestProbe}
import io.circe.Decoder
import io.circe.parser.parse
import org.scalatest.concurrent.{Eventually, ScalaFutures}
import org.scalatest.time.{Minutes, Span}

import org.alephium.appserver.RPCModel._
import org.alephium.crypto.{ED25519, ED25519PrivateKey, ED25519Signature}
import org.alephium.flow.{TaskTrigger, Utils}
import org.alephium.flow.client.{Miner, Node}
import org.alephium.flow.io.StoragesFixture
import org.alephium.flow.platform._
import org.alephium.rpc.model.JsonRPC
import org.alephium.rpc.model.JsonRPC.NotificationUnsafe
import org.alephium.util._

class ServerTest extends AlephiumSpec {
  it should "shutdown the node when Tcp port is used" in new Fixture("1-node") {
    val connection = TestProbe()
    IO(Tcp) ! Tcp.Bind(connection.ref, new InetSocketAddress(defaultMasterPort))

    val server = bootNode(publicPort = defaultMasterPort, brokerId = 0)
    server.system.whenTerminated.futureValue is a[Terminated]
  }

  it should "shutdown the clique when one node of the clique is down" in new Fixture("2-nodes") {

    val server0 = bootNode(publicPort = defaultMasterPort, brokerId = 0)
    val server1 = bootNode(publicPort = generatePort, brokerId      = 1)
    Seq(server0.start(), server1.start()).foreach(_.futureValue is (()))

    server0.stop().futureValue is (())
    server1.system.whenTerminated.futureValue is a[Terminated]
  }

  it should "boot and sync single node clique" in new Fixture("1-node") {
    val server = bootNode(publicPort = defaultMasterPort, brokerId = 0, brokerNum = 1)
    server.start().futureValue is (())
    eventually(request[Boolean](getSelfCliqueSynced) is true)
  }

  it should "boot and sync two nodes clique" in new Fixture("2-nodes") {
    val server0 = bootNode(publicPort = defaultMasterPort, brokerId = 0)
    server0.start.futureValue is (())

    request[Boolean](getSelfCliqueSynced) is false

    val server1 = bootNode(publicPort = generatePort, brokerId = 1)
    server1.start.futureValue is (())

    eventually(request[Boolean](getSelfCliqueSynced) is true)
  }

  it should "work with 2 nodes" in new Fixture("2-nodes") {
    val fromTs = TimeStamp.now()

    val server0 = bootNode(publicPort = defaultMasterPort, brokerId = 0)
    val server1 = bootNode(publicPort = generatePort, brokerId      = 1)
    Seq(server0.start(), server1.start()).foreach(_.futureValue is (()))

    val selfClique = request[SelfClique](getSelfClique)
    val group      = request[Group](getGroup(publicKey))
    val index      = group.group / selfClique.groupNumPerBroker
    val rpcPort    = selfClique.peers(index).rpcPort.get

    request[Balance](getBalance(publicKey), rpcPort) is initialBalance

    startWS(defaultWsMasterPort)

    val tx =
      request[TransferResult](transfer(publicKey, tranferKey, privateKey, transferAmount), rpcPort)

    selfClique.peers.foreach { peer =>
      request[Boolean](startMining, peer.rpcPort.get) is true
    }

    awaitNewBlock(tx.fromGroup, tx.toGroup)
    awaitNewBlock(tx.fromGroup, tx.fromGroup)

    request[Balance](getBalance(publicKey), rpcPort) is
      Balance(initialBalance.balance - transferAmount, 1)

    val createTx =
      request[CreateTransactionResult](createTransaction(publicKey, tranferKey, transferAmount),
                                       rpcPort)

    val tx2 = request[TransferResult](sendTransaction(createTx), rpcPort)

    awaitNewBlock(tx2.fromGroup, tx2.toGroup)
    awaitNewBlock(tx2.fromGroup, tx2.fromGroup)

    selfClique.peers.foreach { peer =>
      request[Boolean](stopMining, peer.rpcPort.get) is true
    }

    request[Balance](getBalance(publicKey), rpcPort) is
      Balance(initialBalance.balance - (2 * transferAmount), 1)

    val toTs = TimeStamp.now()

    //TODO Find a better assertion
    request[FetchResponse](blockflowFetch(fromTs, toTs), rpcPort).blocks.size should be > 16

    server1.stop()
    server0.stop()
  }

  class Fixture(name: String) extends AlephiumActorSpec(name) with ScalaFutures with Eventually {
    override implicit val patienceConfig = PatienceConfig(timeout = Span(1, Minutes))

    val publicKey  = "9f93bf7f1211f510e3d9c4fc7fb933f94830ba83190da62dbfc9baa8b0d36276"
    val privateKey = "a2e3e382cb262ee8af95069f6edfaa4685fc1294805336bafac206c1f115aa96"
    val tranferKey = ED25519.generatePriPub()._2.toHexString

    val initialBalance = Balance(100, 1)
    val transferAmount = 10

    val defaultMasterPort = SocketUtil.temporaryLocalPort(SocketUtil.Both)

    val defaultRpcMasterPort = rpcPort(defaultMasterPort)
    val defaultWsMasterPort  = defaultMasterPort - 200

    def generatePort = SocketUtil.temporaryLocalPort(SocketUtil.Both)

    def rpcPort(port: Int) = port - 100

    val blockNotifyProbe = TestProbe()

    def request[T: Decoder](content: String, port: Int = defaultRpcMasterPort): T = {
      val httpRequest =
        HttpRequest(HttpMethods.POST,
                    uri    = s"http://localhost:${port}",
                    entity = HttpEntity(ContentTypes.`application/json`, content))

      val response = Http().singleRequest(httpRequest).futureValue

      (for {
        json    <- parse(Unmarshal(response.entity).to[String].futureValue)
        request <- json.as[JsonRPC.Response.Success]
        t       <- request.result.as[T]
      } yield t).toOption.get
    }

    @tailrec
    final def awaitNewBlock(from: Int, to: Int): Unit = {
      val timeout = Duration.ofMinutesUnsafe(2).asScala
      blockNotifyProbe.receiveOne(max = timeout) match {
        case TextMessage.Strict(text) =>
          val json         = parse(text).toOption.get
          val notification = json.as[NotificationUnsafe].toOption.get.asNotification.toOption.get
          val blockEntry   = notification.params.as[BlockEntry].toOption.get
          if ((blockEntry.chainFrom equals from) && (blockEntry.chainTo equals to)) ()
          else awaitNewBlock(from, to)
      }
    }

    def buildConfig(publicPort: Int, brokerId: Int, brokerNum: Int = 2) = {
      new PlatformConfigFixture {
        override val configValues = Map(
          ("alephium.network.masterAddress", s"localhost:${masterPort}"),
          ("alephium.network.publicAddress", s"localhost:$publicPort"),
          ("alephium.network.rpcPort", publicPort - 100),
          ("alephium.network.wsPort", publicPort - 200),
          ("alephium.clique.brokerNum", brokerNum),
          ("alephium.broker.brokerId", brokerId)
        ) ++ bootstrap
          .map(address => Map("alephium.bootstrap" -> s"localhost:${address.getPort}"))
          .getOrElse(Map.empty)
        override implicit lazy val config =
          PlatformConfig.build(newConfig, rootPath, None)
      }
    }

    def bootNode(publicPort: Int,
                 brokerId: Int,
                 brokerNum: Int                       = 2,
                 masterPort: Int                      = defaultMasterPort,
                 bootstrap: Option[InetSocketAddress] = None): Server = {
      val platformConfig = buildConfig(publicPort, brokerId, brokerNum)

      val server: Server = new Server {
        implicit val system: ActorSystem =
          ActorSystem(s"$name-${scala.util.Random.nextInt}", platformConfig.config.all)
        implicit val executionContext = system.dispatcher
        implicit val config           = platformConfig.config

        override val mode: Mode = new ModeTest
        lazy val miner: ActorRefT[Miner.Command] = {
          val props =
            Miner.props(mode.node)(config).withDispatcher("akka.actor.mining-dispatcher")
          ActorRefT.build(system, props, s"FairMiner")
        }

        lazy val rpcServer: RPCServer = RPCServer(mode, miner)
      }

      implicit val executionContext = server.system.dispatcher
      ActorRefT.build(
        server.system,
        TaskTrigger.props(
          Await.result(
            for {
              _ <- server.stop()
              _ <- server.system.terminate()
            } yield (()),
            Utils.shutdownTimeout.asScala
          )
        ),
        "GlobalStopper"
      )

      server
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

  class ModeTest(implicit val system: ActorSystem,
                 val config: PlatformConfig,
                 val executionContext: ExecutionContext)
      extends Mode
      with StoragesFixture {

    override val node: Node = Node.build(Mode.defaultBuilders, storages)

    override def stop(): Future[Unit] =
      for {
        _ <- node.stop()
        _ <- Future.successful(storages.close())
        _ <- Future.successful(storages.dESTROYUnsafe())
      } yield (())
  }

}
