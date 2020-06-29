package org.alephium.appserver

import java.net.InetSocketAddress

import scala.annotation.tailrec
import scala.concurrent.{Await, ExecutionContext, Future, Promise}

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{ContentTypes, HttpEntity, HttpMethods, HttpRequest}
import akka.http.scaladsl.model.ws._
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream.scaladsl.{Flow, Keep, Sink, Source}
import akka.testkit.{SocketUtil, TestProbe}
import io.circe.Decoder
import io.circe.parser.parse
import org.scalatest.concurrent.{Eventually, ScalaFutures}
import org.scalatest.time.{Minutes, Span}

import org.alephium.appserver.RPCModel._
import org.alephium.crypto.{ED25519, ED25519PrivateKey, ED25519Signature}
import org.alephium.flow.{AlephiumFlowSpec, TaskTrigger, Utils}
import org.alephium.flow.client.{Miner, Node}
import org.alephium.flow.io.StoragesFixture
import org.alephium.flow.platform._
import org.alephium.protocol.vm.LockupScript
import org.alephium.rpc.model.JsonRPC
import org.alephium.rpc.model.JsonRPC.NotificationUnsafe
import org.alephium.util._

class TestFixture(val name: String) extends TestFixtureLike

trait TestFixtureLike
    extends AlephiumActorSpecLike
    with AlephiumFlowSpec
    with ScalaFutures
    with Eventually {
  override implicit val patienceConfig = PatienceConfig(timeout = Span(1, Minutes))

  def generateAccount: (String, String, String) = {
    val (priKey, pubKey) = ED25519.generatePriPub()
    (LockupScript.p2pkh(pubKey).toBase58, pubKey.toHexString, priKey.toHexString)
  }

  val address                 = "1Dx7Y4RxkCvoYvhQpMnRZi2yJnSbUeoVZY5R1vSYWSk9p"
  val publicKey               = "9b85fef33febd483e055b01af0671714b56c6f3b6f45612589c2d6ae5337fb6d"
  val privateKey              = "9167a5de4cfd4cd36a5ab065e21b2659f34cfd3503fde883b0fa5556d03cb64f"
  val (transferAddress, _, _) = generateAccount

  val initialBalance = Balance(100, 1)
  val transferAmount = 10

  def generatePort = SocketUtil.temporaryLocalPort(SocketUtil.Both)

  def rpcPort(port: Int) = port - 100
  def wsPort(port: Int)  = port - 200

  val defaultMasterPort    = generatePort
  val defaultRpcMasterPort = rpcPort(defaultMasterPort)
  val defaultWsMasterPort  = wsPort(defaultMasterPort)

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

  def transfer(fromPubKey: String,
               toAddress: String,
               amount: Int,
               privateKey: String,
               rpcPort: Int): TxResult = {
    val createTx   = createTransaction(fromPubKey, toAddress, amount)
    val unsignedTx = request[CreateTransactionResult](createTx, rpcPort)
    val sendTx     = sendTransaction(unsignedTx, privateKey)
    request[TxResult](sendTx, rpcPort)
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

  def buildConfig(publicPort: Int,
                  masterPort: Int,
                  brokerId: Int,
                  brokerNum: Int                       = 2,
                  bootstrap: Option[InetSocketAddress] = None) = {
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

  def bootClique(nbOfNodes: Int, bootstrap: Option[InetSocketAddress] = None): Seq[Server] = {
    val masterPort = generatePort

    val servers: Seq[Server] = (0 until nbOfNodes).map { brokerId =>
      val publicPort = if (brokerId equals 0) masterPort else generatePort
      bootNode(publicPort = publicPort,
               masterPort = masterPort,
               brokerId   = brokerId,
               bootstrap  = bootstrap)
    }

    servers
  }

  def bootNode(publicPort: Int,
               brokerId: Int,
               brokerNum: Int                       = 2,
               masterPort: Int                      = defaultMasterPort,
               bootstrap: Option[InetSocketAddress] = None): Server = {
    val platformConfig = buildConfig(publicPort, masterPort, brokerId, brokerNum, bootstrap)

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

  val getInterCliquePeerInfo = jsonRpc("get_inter_clique_peer_info", "{}")

  val getNeighborCliques = jsonRpc("neighbor_cliques", "{}")

  def getGroup(address: String) = jsonRpc("get_group", s"""{"address":"$address"}""")

  def getBalance(address: String) =
    jsonRpc("get_balance", s"""{"address":"$address"}""")

  def createTransaction(fromPubKey: String, toAddress: String, amount: Int) = jsonRpc(
    "create_transaction",
    s"""{"fromKey":"$fromPubKey","toAddress":"$toAddress","value":$amount}"""
  )

  def sendTransaction(createTransactionResult: CreateTransactionResult, privateKey: String) = {
    val signature: ED25519Signature = ED25519.sign(Hex.unsafe(createTransactionResult.hash),
                                                   ED25519PrivateKey.unsafe(Hex.unsafe(privateKey)))
    jsonRpc(
      "send_transaction",
      s"""{"tx":"${createTransactionResult.unsignedTx}","signature":"${signature.toHexString}"}"""
    )
  }

  val startMining = jsonRpc("mining_start", "{}")
  val stopMining  = jsonRpc("mining_stop", "{}")

  def blockflowFetch(fromTs: TimeStamp, toTs: TimeStamp) =
    jsonRpc("blockflow_fetch", s"""{"fromTs":${fromTs.millis},"toTs":${toTs.millis}}""")

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
