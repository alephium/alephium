// Copyright 2018 The Alephium Authors
// This file is part of the alephium project.
//
// The library is free software: you can redistribute it and/or modify
// it under the terms of the GNU Lesser General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// The library is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
// GNU Lesser General Public License for more details.
//
// You should have received a copy of the GNU Lesser General Public License
// along with the library. If not, see <http://www.gnu.org/licenses/>.

package org.alephium.app

import java.net.{DatagramSocket, InetSocketAddress, ServerSocket}
import java.nio.channels.{DatagramChannel, ServerSocketChannel}

import scala.annotation.tailrec
import scala.collection.immutable.ArraySeq
import scala.collection.mutable
import scala.concurrent.{Await, Promise}
import scala.util.control.NonFatal

import akka.actor.ActorRef
import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.ws._
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.io.Tcp
import akka.stream.scaladsl.{Flow, Keep, Sink, Source}
import akka.testkit.TestProbe
import io.circe.{Codec, Decoder}
import io.circe.generic.semiauto.deriveCodec
import io.circe.parser.parse
import org.scalatest.Assertion
import org.scalatest.concurrent.{Eventually, ScalaFutures}
import org.scalatest.time.{Second, Seconds, Span}

import org.alephium.api.ApiModelCodec
import org.alephium.api.model._
import org.alephium.flow.FlowMonitor
import org.alephium.flow.io.{Storages, StoragesFixture}
import org.alephium.flow.setting.{AlephiumConfig, AlephiumConfigFixture}
import org.alephium.protocol.{ALF, PrivateKey, Signature, SignatureSchema}
import org.alephium.protocol.model.{Address, NetworkType}
import org.alephium.rpc.model.JsonRPC.NotificationUnsafe
import org.alephium.util._
import org.alephium.wallet.api.model._

class TestFixture(val name: String) extends TestFixtureLike

// scalastyle:off method.length
// scalastyle:off number.of.methods
trait TestFixtureLike
    extends AlephiumActorSpecLike
    with AlephiumConfigFixture
    with NumericHelpers
    with ApiModelCodec
    with ScalaFutures
    with Eventually {
  override implicit val patienceConfig =
    PatienceConfig(timeout = Span(30, Seconds), interval = Span(1, Second))
  implicit lazy val apiConfig                = ApiConfig.load(newConfig).toOption.get
  implicit lazy val networkType: NetworkType = config.network.networkType

  lazy val blockflowFetchMaxAge = apiConfig.blockflowFetchMaxAge

  def generateAccount: (String, String, String) = {
    val (priKey, pubKey) = SignatureSchema.generatePriPub()
    (Address.p2pkh(networkType, pubKey).toBase58, pubKey.toHexString, priKey.toHexString)
  }

  // the address needs to be in group 0 or 1 for node with broker-id 0
  val address    = "T1Bz5Lri6ensLeYQaPujQKBnRSuxjwCRDsToHyavDxP6Jh"
  val publicKey  = "0238c96456729ada4f0b0fce8d3fb7ba4bd5ae18e716853c43fb113341bfa83a1d"
  val privateKey = "b6f46c5c285ce53caf1c292a4f8aac7ce1670996a3dc37c2ae96217a2141d8f9"
  val mnemonic =
    "woman trophy alarm surface decade reward robust such such inside swallow kit denial mistake marble curtain vehicle kiss auto couch call knee drama pool"
  val (transferAddress, _, _) = generateAccount

  val password = "password"

  val initialBalance = Balance(genesisBalance, 1)
  val transferAmount = ALF.alf(1)

  val usedPort = mutable.Set.empty[Int]
  def generatePort: Int = {
    val tcpPort = 40000 + Random.source.nextInt(5000) * 4

    if (usedPort.contains(tcpPort)) {
      generatePort
    } else {
      val tcp: ServerSocket   = ServerSocketChannel.open().socket()
      val udp: DatagramSocket = DatagramChannel.open().socket()
      val rest: ServerSocket  = ServerSocketChannel.open().socket()
      val ws: ServerSocket    = ServerSocketChannel.open().socket()
      try {
        tcp.bind(new InetSocketAddress("localhost", tcpPort))
        udp.bind(new InetSocketAddress("localhost", tcpPort))
        rest.bind(new InetSocketAddress("localhost", restPort(tcpPort)))
        ws.bind(new InetSocketAddress("localhost", wsPort(tcpPort)))
        usedPort.add(tcpPort)
        tcpPort
      } catch {
        case NonFatal(_) => generatePort
      } finally {
        tcp.close()
        udp.close()
        rest.close()
        ws.close()
      }
    }
  }

  def wsPort(port: Int)   = port - 1
  def restPort(port: Int) = port - 2

  val defaultMasterPort     = generatePort
  val defaultRestMasterPort = restPort(defaultMasterPort)
  val defaultWsMasterPort   = wsPort(defaultMasterPort)
  val defaultWalletPort     = generatePort

  val blockNotifyProbe = TestProbe()

  def httpRequest(method: HttpMethod,
                  endpoint: String,
                  maybeEntity: Option[String]     = None,
                  maybeHeader: Option[HttpHeader] = None): Int => HttpRequest = { port =>
    val request = HttpRequest(method, uri = s"http://localhost:${port}$endpoint")

    val requestWithEntity = maybeEntity match {
      case Some(entity) => request.withEntity(HttpEntity(ContentTypes.`application/json`, entity))
      case None         => request
    }

    val res = maybeHeader match {
      case Some(header) => requestWithEntity.addHeader(header)
      case None         => requestWithEntity
    }
    res
  }

  def httpGet(endpoint: String, maybeEntity: Option[String] = None) =
    httpRequest(HttpMethods.GET, endpoint, maybeEntity)
  def httpPost(endpoint: String, maybeEntity: Option[String] = None) =
    httpRequest(HttpMethods.POST, endpoint, maybeEntity)
  def httpPut(endpoint: String, maybeEntity: Option[String] = None) =
    httpRequest(HttpMethods.PUT, endpoint, maybeEntity)

  def request[T: Decoder](request: Int => HttpRequest, port: Int = defaultRestMasterPort): T = {
    val response = Http().singleRequest(request(port)).futureValue

    (for {
      json <- parse(Unmarshal(response.entity).to[String].futureValue)
      t    <- json.as[T]
    } yield t) match {
      case Right(t)    => t
      case Left(error) => throw new AssertionError(s"circe: $error")
    }
  }

  def requestFailed(request: Int => HttpRequest, port: Int = defaultRestMasterPort): Assertion = {
    val response = Http().singleRequest(request(port)).futureValue
    response.status is StatusCodes.InternalServerError
  }

  def transfer(fromPubKey: String,
               toAddress: String,
               amount: U256,
               privateKey: String,
               restPort: Int): TxResult = eventually {
    val buildTx    = buildTransaction(fromPubKey, toAddress, amount)
    val unsignedTx = request[BuildTransactionResult](buildTx, restPort)
    val sendTx     = sendTransaction(unsignedTx, privateKey)
    val res        = request[TxResult](sendTx, restPort)
    res
  }

  def confirmTx(tx: TxResult, restPort: Int): Assertion = eventually {
    val txStatus = request[TxStatus](getTransactionStatus(tx), restPort)
    checkConfirmations(txStatus)
  }

  def confirmTx(tx: Transfer.Result, restPort: Int): Assertion = eventually {
    val txStatus = request[TxStatus](getTransactionStatus(tx), restPort)
    checkConfirmations(txStatus)
  }

  def checkConfirmations(txStatus: TxStatus): Assertion = {
    print(txStatus) // keep this for easier CI analysis
    print("\n")

    txStatus is a[Confirmed]
    val confirmed = txStatus.asInstanceOf[Confirmed]
    confirmed.chainConfirmations > 1 is true
    confirmed.fromGroupConfirmations > 1 is true
    confirmed.toGroupConfirmations > 1 is true
  }

  implicit val walletResultResultCodec: Codec[WalletRestore.Result] =
    deriveCodec[WalletRestore.Result]
  implicit val transferResultCodec: Codec[Transfer.Result] = deriveCodec[Transfer.Result]

  def transferFromWallet(toAddress: String, amount: U256, restPort: Int): Transfer.Result =
    eventually {
      val walletName =
        request[WalletRestore.Result](restoreWallet(password, mnemonic), restPort).walletName
      val transfer = transferWallet(walletName, toAddress, amount)
      val res      = request[Transfer.Result](transfer, restPort)
      res
    }

  @tailrec
  final def awaitNewBlock(from: Int, to: Int): Unit = {
    val timeout = Duration.ofMinutesUnsafe(2).asScala
    blockNotifyProbe.receiveOne(max = timeout) match {
      case TextMessage.Strict(text) =>
        val json         = parse(text).toOption.get
        val notification = json.as[NotificationUnsafe].toOption.get.asNotification.toOption.get
        val blockEntry   = notification.params.as[BlockEntry].toOption.get
        if ((blockEntry.chainFrom equals from) && (blockEntry.chainTo equals to)) {
          ()
        } else {
          awaitNewBlock(from, to)
        }
    }
  }

  @tailrec
  final def awaitNBlocks(number: Int): Unit = {
    assume(number > 0)
    val timeout = Duration.ofMinutesUnsafe(2).asScala
    blockNotifyProbe.receiveOne(max = timeout) match {
      case TextMessage.Strict(_) =>
        if (number <= 1) {
          ()
        } else {
          awaitNBlocks(number - 1)
        }
    }
  }

  def buildEnv(publicPort: Int,
               masterPort: Int,
               walletPort: Int,
               brokerId: Int,
               brokerNum: Int,
               bootstrap: Option[InetSocketAddress]) = {
    new AlephiumConfigFixture with StoragesFixture {
      override val configValues = Map(
        ("alephium.network.bind-address", s"localhost:$publicPort"),
        ("alephium.network.internal-address", s"localhost:$publicPort"),
        ("alephium.network.coordinator-address", s"localhost:$masterPort"),
        ("alephium.network.external-address", s"localhost:$publicPort"),
        ("alephium.network.ws-port", wsPort(publicPort)),
        ("alephium.network.rest-port", restPort(publicPort)),
        ("alephium.broker.broker-num", brokerNum),
        ("alephium.broker.broker-id", brokerId),
        ("alephium.consensus.block-target-time", "1 seconds"),
        ("alephium.consensus.num-zeros-at-least-in-hash", "8"),
        ("alephium.mining.batch-delay", "100 milli"),
        ("alephium.wallet.port", walletPort),
        ("alephium.wallet.secret-dir", s"${java.nio.file.Files.createTempDirectory("it-test")}")
      )
      override implicit lazy val config = {
        val tmp = AlephiumConfig.load(newConfig).toOption.get
        bootstrap match {
          case Some(address) =>
            tmp.copy(discovery = tmp.discovery.copy(bootstrap = ArraySeq(address)))
          case None => tmp
        }
      }

      val storages: Storages = StoragesFixture.buildStorages(rootPath)
    }
  }

  def bootClique(
      nbOfNodes: Int,
      bootstrap: Option[InetSocketAddress] = None,
      connectionBuild: ActorRef => ActorRefT[Tcp.Command] = ActorRefT.apply): Seq[Server] = {
    val masterPort = generatePort

    val servers: Seq[Server] = (0 until nbOfNodes).map { brokerId =>
      val publicPort = if (brokerId equals 0) masterPort else generatePort
      bootNode(
        publicPort      = publicPort,
        masterPort      = masterPort,
        brokerId        = brokerId,
        walletPort      = generatePort,
        bootstrap       = bootstrap,
        brokerNum       = nbOfNodes,
        connectionBuild = connectionBuild
      )
    }

    servers
  }

  def bootNode(publicPort: Int,
               brokerId: Int,
               brokerNum: Int                       = 2,
               masterPort: Int                      = defaultMasterPort,
               walletPort: Int                      = defaultWalletPort,
               bootstrap: Option[InetSocketAddress] = None,
               connectionBuild: ActorRef => ActorRefT[Tcp.Command] = ActorRefT.apply): Server = {
    val platformEnv =
      buildEnv(publicPort, masterPort, walletPort, brokerId, brokerNum, bootstrap)

    val server: Server = new Server {
      implicit val system: ActorSystem =
        ActorSystem(s"$name-${Random.source.nextInt}", platformEnv.newConfig)
      implicit val executionContext = system.dispatcher

      val defaultNetwork = platformEnv.config.network
      val network        = defaultNetwork.copy(connectionBuild = connectionBuild)

      implicit val config    = platformEnv.config.copy(network = network)
      implicit val apiConfig = ApiConfig.load(platformEnv.newConfig).toOption.get
      val storages           = platformEnv.storages

      override lazy val blocksExporter: BlocksExporter =
        new BlocksExporter(node.blockFlow, rootPath)(config.broker)

      ActorRefT.build(
        system,
        FlowMonitor.props(
          Await.result(
            for {
              _ <- this.stop()
              _ <- this.system.terminate()
            } yield (),
            FlowMonitor.shutdownTimeout.asScala
          )
        )
      )
    }

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

  val getSelfClique =
    httpGet(s"/infos/self-clique") // jsonRpc("self_clique_synced", "{}") = jsonRpc("self_clique", "{}")

  val getInterCliquePeerInfo =
    httpGet(s"/infos/inter-clique-peer-info") //jsonRpc("get_inter_clique_peer_info", "{}")

  val getMisbehaviors =
    httpGet(s"/infos/misbehaviors")

  def getGroup(address: String) =
    httpGet(s"/addresses/$address/group")

  def getBalance(address: String) =
    httpGet(s"/addresses/$address/balance")

  def getChainInfo(fromGroup: Int, toGroup: Int) =
    httpGet(s"/blockflow/chains?fromGroup=$fromGroup&toGroup=$toGroup")

  def buildTransaction(fromPubKey: String, toAddress: String, amount: U256) =
    httpGet(
      s"/transactions/build?fromKey=$fromPubKey&toAddress=$toAddress&value=$amount"
    )

  def restoreWallet(password: String, mnemonic: String) =
    httpPut(
      s"/wallets",
      Some(s"""{"password":"${password}","mnemonic":"${mnemonic}"}""")
    )

  def transferWallet(walletName: String, address: String, amount: U256) = {
    httpPost(
      s"/wallets/${walletName}/transfer",
      Some(s"""{"address":"${address}","amount":"${amount}"}""")
    )
  }
  def sendTransaction(buildTransactionResult: BuildTransactionResult, privateKey: String) = {
    val signature: Signature = SignatureSchema.sign(buildTransactionResult.txId.bytes,
                                                    PrivateKey.unsafe(Hex.unsafe(privateKey)))
    httpPost(
      "/transactions/send",
      Some(
        s"""{"unsignedTx":"${buildTransactionResult.unsignedTx}","signature":"${signature.toHexString}"}""")
    )
  }
  def getTransactionStatus(tx: TxResult) = {
    httpGet(
      s"/transactions/status?txId=${tx.txId.toHexString}&fromGroup=${tx.fromGroup}&toGroup=${tx.toGroup}")
  }
  def getTransactionStatus(tx: Transfer.Result) = {
    httpGet(
      s"/transactions/status?txId=${tx.txId.toHexString}&fromGroup=${tx.fromGroup}&toGroup=${tx.toGroup}")
  }

  def compileFilang(code: String) = {
    httpPost(s"/contracts/compile", Some(code))
  }

  def buildContract(query: String) = {
    httpPost(s"/contracts/build", Some(query))
  }

  def sendContract(contract: String) = {
    httpPost(s"/contracts/send", Some(contract))
  }

  val startMining = httpPost("/miners?action=start-mining")
  val stopMining  = httpPost("/miners?action=stop-mining")

  def exportBlocks(filename: String) =
    httpPost(s"/export-blocks", Some(s"""{"filename": "${filename}"}"""))

  def blockflowFetch(fromTs: TimeStamp, toTs: TimeStamp) =
    httpGet(s"/blockflow?fromTs=${fromTs.millis}&toTs=${toTs.millis}")
}
// scalastyle:on method.length
