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
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Random
import scala.util.control.NonFatal

import akka.Done
import akka.actor.{ActorRef, ActorSystem, CoordinatedShutdown}
import akka.io.Tcp
import akka.testkit.TestProbe
import io.vertx.core.Vertx
import io.vertx.core.http.WebSocketBase
import org.scalatest.Assertion
import org.scalatest.concurrent.{Eventually, ScalaFutures}
import org.scalatest.time.{Seconds, Span}
import sttp.model.StatusCode
import sttp.tapir.server.vertx.VertxFutureServerInterpreter._

import org.alephium.api.ApiModelCodec
import org.alephium.api.model._
import org.alephium.flow.io.{Storages, StoragesFixture}
import org.alephium.flow.setting.{AlephiumConfig, AlephiumConfigFixture}
import org.alephium.http.HttpFixture
import org.alephium.json.Json._
import org.alephium.protocol.{ALF, PrivateKey, Signature, SignatureSchema}
import org.alephium.protocol.model.{Address, NetworkType}
import org.alephium.protocol.vm.LockupScript
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
    with HttpFixture
    with ScalaFutures
    with Eventually {

  private val vertx      = Vertx.vertx()
  private val httpClient = vertx.createHttpClient()

  implicit override val patienceConfig =
    PatienceConfig(timeout = Span(60, Seconds), interval = Span(2, Seconds))
  implicit lazy val apiConfig                = ApiConfig.load(newConfig)
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

  val initialBalance = Balance(genesisBalance, 0, 1)
  val transferAmount = ALF.alf(1)

  val usedPort = mutable.Set.empty[Int]
  def generatePort: Int = {
    val tcpPort = 40000 + Random.nextInt(5000) * 4

    if (usedPort.contains(tcpPort)) {
      generatePort
    } else {
      val tcp: ServerSocket   = ServerSocketChannel.open().socket()
      val udp: DatagramSocket = DatagramChannel.open().socket()
      val rest: ServerSocket  = ServerSocketChannel.open().socket()
      val ws: ServerSocket    = ServerSocketChannel.open().socket()
      try {
        tcp.setReuseAddress(true)
        tcp.bind(new InetSocketAddress("127.0.0.1", tcpPort))
        udp.setReuseAddress(true)
        udp.bind(new InetSocketAddress("127.0.0.1", tcpPort))
        rest.setReuseAddress(true)
        rest.bind(new InetSocketAddress("127.0.0.1", restPort(tcpPort)))
        ws.setReuseAddress(true)
        ws.bind(new InetSocketAddress("127.0.0.1", wsPort(tcpPort)))
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

  def wsPort(port: Int)    = port - 1
  def restPort(port: Int)  = port - 2
  def minerPort(port: Int) = port - 3

  val defaultMasterPort     = generatePort
  val defaultRestMasterPort = restPort(defaultMasterPort)
  val defaultWsMasterPort   = wsPort(defaultMasterPort)
  val defaultWalletPort     = generatePort

  val blockNotifyProbe = TestProbe()

  def unitRequest(request: Int => HttpRequest, port: Int = defaultRestMasterPort): Assertion = {
    val response = request(port).send(backend)
    response.code is StatusCode.Ok
  }

  def request[T: Reader](request: Int => HttpRequest, port: Int = defaultRestMasterPort): T = {
    eventually {
      val response = request(port).send(backend)

      val body = response.body match {
        case Right(r) => r
        case Left(l)  => l
      }
      read[T](body)
    }
  }

  def requestFailed(
      request: Int => HttpRequest,
      port: Int = defaultRestMasterPort,
      statusCode: StatusCode
  ): Assertion = {
    val response = request(port).send(backend)
    response.code is statusCode
  }

  def transfer(
      fromPubKey: String,
      toAddress: String,
      amount: U256,
      privateKey: String,
      restPort: Int
  ): TxResult = eventually {
    val buildTx    = buildTransaction(fromPubKey, toAddress, amount)
    val unsignedTx = request[BuildTransactionResult](buildTx, restPort)
    val submitTx   = submitTransaction(unsignedTx, privateKey)
    val res        = request[TxResult](submitTx, restPort)
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

  implicit val walletResultResultReadWriter: ReadWriter[WalletRestore.Result] =
    macroRW[WalletRestore.Result]
  implicit val transferResultReadWriter: ReadWriter[Transfer.Result] = macroRW[Transfer.Result]

  def transferFromWallet(toAddress: String, amount: U256, restPort: Int): Transfer.Result =
    eventually {
      val walletName =
        request[WalletRestore.Result](restoreWallet(password, mnemonic), restPort).walletName
      val transfer = transferWallet(walletName, toAddress, amount)
      val res      = request[Transfer.Result](transfer, restPort)
      res
    }

  final def awaitNBlocksPerChain(number: Int): Unit = {
    val buffer  = Array.fill(groups0)(Array.ofDim[Int](groups0))
    val timeout = Duration.ofMinutesUnsafe(2).asScala

    @tailrec
    def iter(): Unit = {
      blockNotifyProbe.receiveOne(max = timeout) match {
        case text: String =>
          val notification = read[NotificationUnsafe](text).asNotification.toOption.get
          val blockEntry   = read[BlockEntry](notification.params)
          buffer(blockEntry.chainFrom)(blockEntry.chainTo) += 1
          if (buffer.forall(_.forall(_ >= number))) () else iter()
      }
    }

    iter()
  }

  @tailrec
  final def awaitNBlocks(number: Int): Unit = {
    assume(number > 0)
    val timeout = Duration.ofMinutesUnsafe(2).asScala
    blockNotifyProbe.receiveOne(max = timeout) match {
      case _: String =>
        if (number <= 1) {
          ()
        } else {
          awaitNBlocks(number - 1)
        }
    }
  }

  def buildEnv(
      publicPort: Int,
      masterPort: Int,
      walletPort: Int,
      brokerId: Int,
      brokerNum: Int,
      bootstrap: Option[InetSocketAddress],
      configOverrides: Map[String, Any]
  ) = {
    new AlephiumConfigFixture with StoragesFixture {
      override val configValues = Map(
        ("alephium.network.bind-address", s"127.0.0.1:$publicPort"),
        ("alephium.network.internal-address", s"127.0.0.1:$publicPort"),
        ("alephium.network.coordinator-address", s"127.0.0.1:$masterPort"),
        ("alephium.network.external-address", s"127.0.0.1:$publicPort"),
        ("alephium.network.ws-port", wsPort(publicPort)),
        ("alephium.network.rest-port", restPort(publicPort)),
        ("alephium.network.miner-api-port", minerPort(publicPort)),
        ("alephium.broker.broker-num", brokerNum),
        ("alephium.broker.broker-id", brokerId),
        ("alephium.consensus.block-target-time", "1 seconds"),
        ("alephium.consensus.num-zeros-at-least-in-hash", "8"),
        ("alephium.mining.batch-delay", "200 milli"),
        ("alephium.wallet.port", walletPort),
        ("alephium.wallet.secret-dir", s"${java.nio.file.Files.createTempDirectory("it-test")}")
      ) ++ configOverrides
      implicit override lazy val config = {
        val minerAddresses =
          genesisKeys.map(p => Address(NetworkType.Testnet, LockupScript.p2pkh(p._2)))

        val tmp0 = AlephiumConfig.load(newConfig)
        val tmp1 = tmp0.copy(mining = tmp0.mining.copy(minerAddresses = Some(minerAddresses)))
        bootstrap match {
          case Some(address) =>
            tmp1.copy(discovery = tmp1.discovery.copy(bootstrap = ArraySeq(address)))
          case None => tmp1
        }
      }

      val storages: Storages = StoragesFixture.buildStorages(rootPath)
    }
  }

  def bootClique(
      nbOfNodes: Int,
      bootstrap: Option[InetSocketAddress] = None,
      connectionBuild: ActorRef => ActorRefT[Tcp.Command] = ActorRefT.apply,
      configOverrides: Map[String, Any] = Map.empty
  ): Seq[Server] = {
    val masterPort = generatePort

    val servers: Seq[Server] = (0 until nbOfNodes).map { brokerId =>
      val publicPort = if (brokerId equals 0) masterPort else generatePort
      bootNode(
        publicPort = publicPort,
        masterPort = masterPort,
        brokerId = brokerId,
        walletPort = generatePort,
        bootstrap = bootstrap,
        brokerNum = nbOfNodes,
        connectionBuild = connectionBuild,
        configOverrides = configOverrides
      )
    }

    servers
  }

  def bootNode(
      publicPort: Int,
      brokerId: Int,
      brokerNum: Int = 2,
      masterPort: Int = defaultMasterPort,
      walletPort: Int = defaultWalletPort,
      bootstrap: Option[InetSocketAddress] = None,
      connectionBuild: ActorRef => ActorRefT[Tcp.Command] = ActorRefT.apply,
      configOverrides: Map[String, Any] = Map.empty
  ): Server = {
    val platformEnv =
      buildEnv(publicPort, masterPort, walletPort, brokerId, brokerNum, bootstrap, configOverrides)

    val server: Server = new Server {
      val flowSystem: ActorSystem =
        ActorSystem(s"flow-${Random.nextInt()}", platformEnv.newConfig)
      val httpSystem: ActorSystem =
        ActorSystem(s"http-${Random.nextInt()}", platformEnv.newConfig)
      implicit val executionContext = ExecutionContext.Implicits.global

      val defaultNetwork = platformEnv.config.network
      val network        = defaultNetwork.copy(connectionBuild = connectionBuild)

      implicit val config    = platformEnv.config.copy(network = network)
      implicit val apiConfig = ApiConfig.load(platformEnv.newConfig)
      val storages           = platformEnv.storages

      override lazy val blocksExporter: BlocksExporter =
        new BlocksExporter(node.blockFlow, rootPath)(config.broker)

      CoordinatedShutdown(flowSystem).addTask(
        CoordinatedShutdown.PhaseBeforeActorSystemTerminate,
        "Shutdown services"
      ) { () =>
        for {
          _ <- this.stopSubServices()
          _ <- httpSystem.terminate()
        } yield Done
      }

      override def stop(): Future[Unit] = flowSystem.terminate().map(_ => ())
    }

    server
  }

  def startWS(port: Int): Future[WebSocketBase] = {
    implicit val executionContext = ExecutionContext.Implicits.global
    httpClient.webSocket(port, "127.0.0.1", "/events").asScala.map { ws =>
      ws.textMessageHandler { blockNotify =>
        blockNotifyProbe.ref ! blockNotify
      }
    }
  }

  def jsonRpc(method: String, params: String): String =
    s"""{"jsonrpc":"2.0","id": 0,"method":"$method","params": $params}"""

  val getSelfClique =
    httpGet(s"/infos/self-clique")

  val getInterCliquePeerInfo =
    httpGet(s"/infos/inter-clique-peer-info")

  val getDiscoveredNeighbors =
    httpGet(s"/infos/discovered-neighbors")

  val getMisbehaviors =
    httpGet(s"/infos/misbehaviors")

  def getGroup(address: String) =
    httpGet(s"/addresses/$address/group")

  def getBalance(address: String) =
    httpGet(s"/addresses/$address/balance")

  def getChainInfo(fromGroup: Int, toGroup: Int) =
    httpGet(s"/blockflow/chains?fromGroup=$fromGroup&toGroup=$toGroup")

  def buildTransaction(fromPubKey: String, toAddress: String, amount: U256) =
    httpPost(
      "/transactions/build",
      Some(s"""
        |{
        |  "fromPublicKey": "$fromPubKey",
        |  "destinations": [
        |    {
        |      "address": "$toAddress",
        |      "amount": "$amount"
        |    }
        |  ]
        |}
        """.stripMargin)
    )

  def restoreWallet(password: String, mnemonic: String) =
    httpPut(
      s"/wallets",
      Some(s"""{"password":"${password}","mnemonic":"${mnemonic}"}""")
    )

  def transferWallet(walletName: String, address: String, amount: U256) = {
    httpPost(
      s"/wallets/${walletName}/transfer",
      Some(s"""{"destinations":[{"address":"${address}","amount":"${amount}"}]}""")
    )
  }
  def submitTransaction(buildTransactionResult: BuildTransactionResult, privateKey: String) = {
    val signature: Signature = SignatureSchema.sign(
      buildTransactionResult.txId.bytes,
      PrivateKey.unsafe(Hex.unsafe(privateKey))
    )
    httpPost(
      "/transactions/submit",
      Some(
        s"""{"unsignedTx":"${buildTransactionResult.unsignedTx}","signature":"${signature.toHexString}"}"""
      )
    )
  }
  def getTransactionStatus(tx: TxResult) = {
    httpGet(
      s"/transactions/status?txId=${tx.txId.toHexString}&fromGroup=${tx.fromGroup}&toGroup=${tx.toGroup}"
    )
  }
  def getTransactionStatus(tx: Transfer.Result) = {
    httpGet(
      s"/transactions/status?txId=${tx.txId.toHexString}&fromGroup=${tx.fromGroup}&toGroup=${tx.toGroup}"
    )
  }

  def compileFilang(code: String) = {
    httpPost(s"/contracts/compile", Some(code))
  }

  def buildContract(query: String) = {
    httpPost(s"/contracts/build", Some(query))
  }

  def submitContract(contract: String) = {
    httpPost(s"/contracts/submit", Some(contract))
  }

  val startMining = httpPost("/miners?action=start-mining")
  val stopMining  = httpPost("/miners?action=stop-mining")

  def exportBlocks(filename: String) =
    httpPost(s"/export-blocks", Some(s"""{"filename": "${filename}"}"""))

  def blockflowFetch(fromTs: TimeStamp, toTs: TimeStamp) =
    httpGet(s"/blockflow?fromTs=${fromTs.millis}&toTs=${toTs.millis}")
}
// scalastyle:on method.length
