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

import java.net.InetSocketAddress

import scala.annotation.tailrec
import scala.collection.immutable.ArraySeq
import scala.concurrent.{Await, Promise}

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.ws._
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream.scaladsl.{Flow, Keep, Sink, Source}
import akka.testkit.{SocketUtil, TestProbe}
import io.circe.{Codec, Decoder}
import io.circe.generic.semiauto.deriveCodec
import io.circe.parser.parse
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

  val address    = "T1D9PBcRXK5uzrNYokNMB7oh6JpW86sZajJ5gD845cshED"
  val publicKey  = "03ee6c463816f1b7f3d23ccff248fa73d114ac5eabd616b1fd0d35e6f5ec1eb092"
  val privateKey = "a13841774e62ef76b4d9aac1531b68a46e93baf09ba9c692d57a0edc64b973d4"
  val mnemonic =
    "inform ocean drama click lazy ridge unit cause decrease notable cat trumpet describe mushroom truly gospel chaos bless female web festival birth bread embark"
  val (transferAddress, _, _) = generateAccount

  val password = "password"

  val initialBalance = Balance(genesisBalance, 1)
  val transferAmount = ALF.alf(1)

  def generatePort = SocketUtil.temporaryLocalPort(SocketUtil.Both)

  def wsPort(port: Int)   = port - 200
  def restPort(port: Int) = port - 300

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
        ("alephium.network.rpc-port", publicPort - 100),
        ("alephium.network.ws-port", publicPort - 200),
        ("alephium.network.rest-port", publicPort - 300),
        ("alephium.broker.broker-num", brokerNum),
        ("alephium.broker.broker-id", brokerId),
        ("alephium.consensus.block-target-time", "1 seconds"),
        ("alephium.consensus.num-zeros-at-least-in-hash", "8"),
        ("alephium.mining.batch-delay", "500 milli"), // increase this if still flaky
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

  def bootClique(nbOfNodes: Int, bootstrap: Option[InetSocketAddress] = None): Seq[Server] = {
    val masterPort = generatePort

    val servers: Seq[Server] = (0 until nbOfNodes).map { brokerId =>
      val publicPort = if (brokerId equals 0) masterPort else generatePort
      bootNode(publicPort = publicPort,
               masterPort = masterPort,
               brokerId   = brokerId,
               walletPort = generatePort,
               bootstrap  = bootstrap,
               brokerNum  = nbOfNodes)
    }

    servers
  }

  def bootNode(publicPort: Int,
               brokerId: Int,
               brokerNum: Int                       = 2,
               masterPort: Int                      = defaultMasterPort,
               walletPort: Int                      = defaultWalletPort,
               bootstrap: Option[InetSocketAddress] = None): Server = {
    val platformEnv =
      buildEnv(publicPort, masterPort, walletPort, brokerId, brokerNum, bootstrap)

    val server: Server = new Server {
      implicit val system: ActorSystem =
        ActorSystem(s"$name-${Random.source.nextInt}", platformEnv.newConfig)
      implicit val executionContext = system.dispatcher

      implicit val config    = platformEnv.config
      implicit val apiConfig = ApiConfig.load(platformEnv.newConfig).toOption.get
      val storages           = platformEnv.storages
      val blockExporter: BlocksExporter =
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

      override val node: Node = Node.build(platformEnv.storages)
      lazy val miner: ActorRefT[Miner.Command] = {
        val props = Miner
          .props(node)
          .withDispatcher("akka.actor.mining-dispatcher")
        ActorRefT.build(system, props, s"FairMiner")
      }

      private val walletApp: Option[WalletApp] =
        Option.when(config.network.isCoordinator) {
          val walletConfig: WalletConfig = WalletConfig(
            config.wallet.port,
            config.wallet.secretDir,
            config.network.networkType,
            WalletConfig.BlockFlow(
              apiConfig.networkInterface.getHostAddress,
              config.network.restPort,
              config.broker.groups
            )
          )

          new WalletApp(walletConfig)
        }

      lazy val blocksExporter = new BlocksExporter(node)
      lazy val restServer: RestServer =
        RestServer(node, miner, blocksExporter, walletApp.map(_.walletServer))
      lazy val webSocketServer: WebSocketServer     = WebSocketServer(node)
      lazy val walletService: Option[WalletService] = walletApp.map(_.walletService)
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

  val getSelfCliqueSynced =
    httpGet(s"/infos/self-clique-synced") // jsonRpc("self_clique_synced", "{}")

  val getInterCliquePeerInfo =
    httpGet(s"/infos/inter-clique-peer-info") //jsonRpc("get_inter_clique_peer_info", "{}")

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
    val signature: Signature = SignatureSchema.sign(Hex.unsafe(buildTransactionResult.hash),
                                                    PrivateKey.unsafe(Hex.unsafe(privateKey)))
    httpPost(
      "/transactions/send",
      Some(
        s"""{"tx":"${buildTransactionResult.unsignedTx}","signature":"${signature.toHexString}"}""")
    )
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
