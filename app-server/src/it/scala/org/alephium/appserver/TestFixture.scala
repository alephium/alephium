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

package org.alephium.appserver

import java.net.InetSocketAddress

import scala.annotation.tailrec
import scala.collection.immutable.ArraySeq
import scala.concurrent.{Await, Promise}

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.RawHeader
import akka.http.scaladsl.model.ws._
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream.scaladsl.{Flow, Keep, Sink, Source}
import akka.testkit.{SocketUtil, TestProbe}
import io.circe.Decoder
import io.circe.parser.parse
import org.scalatest.concurrent.{Eventually, ScalaFutures}
import org.scalatest.time.{Minutes, Span}

import org.alephium.appserver.ApiModel._
import org.alephium.crypto.Sha256
import org.alephium.flow.{AlephiumFlowSpec, FlowMonitor}
import org.alephium.flow.client.{Miner, Node}
import org.alephium.flow.io.StoragesFixture
import org.alephium.flow.setting.{AlephiumConfig, AlephiumConfigFixture}
import org.alephium.protocol.{ALF, Hash, PrivateKey, Signature, SignatureSchema}
import org.alephium.protocol.model.{Address, NetworkType}
import org.alephium.rpc.model.JsonRPC.NotificationUnsafe
import org.alephium.util._
import org.alephium.wallet.WalletApp
import org.alephium.wallet.config.WalletConfig
import org.alephium.wallet.service.WalletService

class TestFixture(val name: String) extends TestFixtureLike

// scalastyle:off method.length
trait TestFixtureLike
    extends AlephiumActorSpecLike
    with AlephiumFlowSpec
    with AlephiumConfigFixture
    with ApiModelCodec
    with ScalaFutures
    with Eventually {
  override implicit val patienceConfig       = PatienceConfig(timeout = Span(1, Minutes))
  implicit lazy val apiConfig                = ApiConfig.load(newConfig).toOption.get
  implicit lazy val networkType: NetworkType = config.chains.networkType

  def generateAccount: (String, String, String) = {
    val (priKey, pubKey) = SignatureSchema.generatePriPub()
    (Address.p2pkh(networkType, pubKey).toBase58, pubKey.toHexString, priKey.toHexString)
  }

  val address                 = "T1CarrVGRS2YvEoPLgY6HJvyCAcqW91buYWRJ4roRDCPPg"
  val publicKey               = "033cd0876b492fea9c5c61f616ca3faf006097959c67f663ce7653ecb0b71b4eb4"
  val privateKey              = "bf38aa6f383b14e678a5fd885b93dd97d1add94aaddb70b7cfb6e4c5e8330fe9"
  val (transferAddress, _, _) = generateAccount

  val apiKey     = Hash.generate.toHexString
  val apiKeyHash = Sha256.hash(apiKey)

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
    httpRequest(HttpMethods.POST, endpoint, maybeEntity, Some(RawHeader("X-API-KEY", apiKey)))

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
    val createTx   = createTransaction(fromPubKey, toAddress, amount)
    val unsignedTx = request[CreateTransactionResult](createTx, restPort)
    val sendTx     = sendTransaction(unsignedTx, privateKey)
    val res        = request[TxResult](sendTx, restPort)
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

  def buildEnv(publicPort: Int,
               masterPort: Int,
               walletPort: Int,
               brokerId: Int,
               brokerNum: Int                       = 2,
               bootstrap: Option[InetSocketAddress] = None) = {
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
        ("alephium.api.api-key-hash", apiKeyHash.toHexString),
        ("alephium.wallet.port", walletPort)
      )
      override implicit lazy val config = {
        val tmp = AlephiumConfig.load(newConfig).toOption.get
        bootstrap match {
          case Some(address) =>
            tmp.copy(discovery = tmp.discovery.copy(bootstrap = ArraySeq(address)))
          case None => tmp
        }
      }
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
    val platformEnv        = buildEnv(publicPort, masterPort, walletPort, brokerId, brokerNum, bootstrap)
    implicit val apiConfig = ApiConfig.load(platformEnv.newConfig).toOption.get

    val server: Server = new Server {
      implicit val system: ActorSystem =
        ActorSystem(s"$name-${Random.source.nextInt}", platformEnv.newConfig)
      implicit val executionContext = system.dispatcher
      implicit val config           = platformEnv.config

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
          .props(node)(config.broker, config.mining)
          .withDispatcher("akka.actor.mining-dispatcher")
        ActorRefT.build(system, props, s"FairMiner")
      }

      private val walletApp: Option[WalletApp] =
        Option.when(config.network.isCoordinator) {
          val walletConfig: WalletConfig = WalletConfig(
            config.wallet.port,
            config.wallet.secretDir,
            config.chains.networkType,
            WalletConfig.BlockFlow(
              apiConfig.networkInterface.getHostAddress,
              config.network.rpcPort,
              config.broker.groups
            )
          )

          new WalletApp(walletConfig)
        }

      lazy val restServer: RestServer               = RestServer(node, miner, walletApp.map(_.walletServer))
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

  def createTransaction(fromPubKey: String, toAddress: String, amount: U256) =
    httpGet(
      s"/unsigned-transactions?fromKey=$fromPubKey&toAddress=$toAddress&value=$amount"
    )

  def sendTransaction(createTransactionResult: CreateTransactionResult, privateKey: String) = {
    val signature: Signature = SignatureSchema.sign(Hex.unsafe(createTransactionResult.hash),
                                                    PrivateKey.unsafe(Hex.unsafe(privateKey)))
    httpPost(
      "/transactions",
      Some(
        s"""{"tx":"${createTransactionResult.unsignedTx}","signature":"${signature.toHexString}"}""")
    )
  }

  def compileFilang(code: String) = {
    httpPost(s"/compile", Some(code))
  }

  def createContract(query: String) = {
    httpPost(s"/unsigned-contracts", Some(query))
  }

  def sendContract(contract: String) = {
    httpPost(s"/contracts", Some(contract))
  }

  val startMining = httpPost("/miners?action=start-mining")
  val stopMining  = httpPost("/miners?action=stop-mining")

  def blockflowFetch(fromTs: TimeStamp, toTs: TimeStamp) =
    httpGet(s"/blockflow?fromTs=${fromTs.millis}&toTs=${toTs.millis}")
}
// scalastyle:on method.length
