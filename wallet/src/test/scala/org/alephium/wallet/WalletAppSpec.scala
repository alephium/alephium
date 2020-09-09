package org.alephium.wallet

import java.net.InetAddress
import java.nio.file.Files

import scala.concurrent.duration._

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.testkit.{RouteTestTimeout, ScalatestRouteTest}
import akka.testkit.SocketUtil
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport
import io.circe.{Decoder, DecodingFailure, HCursor, Json}
import org.scalatest.concurrent.ScalaFutures

import org.alephium.protocol.Hash
import org.alephium.protocol.config.GroupConfig
import org.alephium.protocol.model.{NetworkType, TxGenerators}
import org.alephium.serde.serialize
import org.alephium.util.{AlephiumSpec, Hex}
import org.alephium.wallet.api.model
import org.alephium.wallet.config.WalletConfig

class WalletAppSpec
    extends AlephiumSpec
    with ScalatestRouteTest
    with FailFastCirceSupport
    with ScalaFutures {
  implicit val defaultTimeout = RouteTestTimeout(5.seconds)

  val localhost: InetAddress = InetAddress.getLocalHost
  val blockFlowPort          = SocketUtil.temporaryLocalPort(SocketUtil.Both)
  val walletPort             = SocketUtil.temporaryLocalPort(SocketUtil.Both)

  val groupNum = 4

  implicit val groupConfig: GroupConfig = new GroupConfig {
    override def groups: Int = groupNum
  }
  val blockFlowMock =
    new WalletAppSpec.BlockFlowServerMock(localhost, blockFlowPort)
  val blockflowBinding = blockFlowMock.server.futureValue

  val tempSecretDir = Files.createTempDirectory("blockflow-wallet-spec")
  tempSecretDir.toFile.deleteOnExit

  val networkType = NetworkType.Mainnet

  val config = WalletConfig(
    walletPort,
    tempSecretDir,
    networkType,
    WalletConfig.BlockFlow(localhost.getHostAddress, blockFlowPort, groupNum))

  val walletApp: WalletApp =
    new WalletApp(config)

  val routes: Route = walletApp.routes

  val password                 = Hash.generate.toHexString
  var mnemonic: model.Mnemonic = model.Mnemonic(Seq.empty)
  var address                  = ""
  val transferAddress          = Hash.generate.toHexString
  val amount                   = 10

  val creationJson     = s"""{"password":"$password"}"""
  val unlockJson       = s"""{"password":"$password"}"""
  val transferJson     = s"""{"address":"$transferAddress","amount":$amount}"""
  lazy val restoreJson = s"""{"password":"$password","mnemonic":"${mnemonic}"}"""

  def create()     = Post(s"/wallet/create", entity(creationJson)) ~> routes
  def unlock()     = Post(s"/wallet/unlock", entity(unlockJson)) ~> routes
  def lock()       = Post(s"/wallet/lock") ~> routes
  def getBalance() = Get(s"/wallet/balance") ~> routes
  def getAddress() = Get(s"/wallet/address") ~> routes
  def transfer()   = Post(s"/wallet/transfer", entity(transferJson)) ~> routes
  def restore()    = Post(s"/wallet/restore", entity(restoreJson)) ~> routes

  def entity(json: String) = HttpEntity(ContentTypes.`application/json`, json)

  it should "work" in {
    unlock() ~> check {
      status is StatusCodes.BadRequest
    }

    create() ~> check {
      mnemonic = responseAs[model.Mnemonic]
      status is StatusCodes.OK
    }

    unlock() ~> check {
      status is StatusCodes.OK
    }

    //Lock is idempotent
    (0 to 10).foreach { _ =>
      lock() ~> check {
        status is StatusCodes.OK
      }
    }

    getBalance() ~> check {
      status is StatusCodes.BadRequest
    }

    getAddress() ~> check {
      status is StatusCodes.BadRequest
    }

    transfer() ~> check {
      status is StatusCodes.BadRequest
    }

    unlock()

    getBalance() ~> check {
      responseAs[Long] is 42
      status is StatusCodes.OK
    }

    getAddress() ~> check {
      address = responseAs[String]
      status is StatusCodes.OK
    }

    transfer() ~> check {
      status is StatusCodes.OK
      responseAs[model.Transfer.Result] is model.Transfer.Result("txId")
    }

    restore() ~> check {
      status is StatusCodes.OK
    }

    getAddress() ~> check {
      status is StatusCodes.BadRequest
    }

    unlock()

    getAddress() ~> check {
      responseAs[String] is address
      status is StatusCodes.OK
    }

    Get(s"/docs") ~> routes ~> check {
      status is StatusCodes.PermanentRedirect
    }

    Get(s"/docs/openapi.yaml") ~> routes ~> check {
      status is StatusCodes.OK
    }

    walletApp.stop().futureValue
    tempSecretDir.toFile.listFiles.foreach(_.deleteOnExit())
  }
}

object WalletAppSpec {
  import org.alephium.wallet.web.BlockFlowClient._

  implicit val jsonRpcDecoder: Decoder[JsonRpc] = new Decoder[JsonRpc] {
    def decode(c: HCursor, method: String, params: Json): Decoder.Result[JsonRpc] = {
      method match {
        case "send_transaction"   => params.as[SendTransaction]
        case "create_transaction" => params.as[CreateTransaction]
        case "get_balance"        => params.as[GetBalance]
        case "self_clique"        => Right(GetSelfClique)
        case _                    => Left(DecodingFailure(s"$method not supported", c.history))
      }
    }
    final def apply(c: HCursor): Decoder.Result[JsonRpc] =
      for {
        method  <- c.downField("method").as[String]
        params  <- c.downField("params").as[Json]
        jsonRpc <- decode(c, method, params)
      } yield jsonRpc
  }

  class BlockFlowServerMock(address: InetAddress, port: Int)(implicit val groupConfig: GroupConfig,
                                                             system: ActorSystem)
      extends FailFastCirceSupport
      with TxGenerators {

    private val peer = PeerAddress(address, Some(port), None)

    val routes: Route =
      post {
        entity(as[JsonRpc]) {
          case GetSelfClique =>
            complete(Result(SelfClique(Seq(peer, peer), 2)))
          case GetBalance(_) =>
            complete(Result(Balance(42, 1)))
          case CreateTransaction(_, _, _) =>
            val unsignedTx = transactionGen().sample.get.unsigned
            complete(
              Result(
                CreateTransactionResult(Hex.toHexString(serialize(unsignedTx)),
                                        Hex.toHexString(unsignedTx.hash.bytes),
                                        unsignedTx.fromGroup.value,
                                        unsignedTx.toGroup.value)
              ))
          case SendTransaction(_, _) =>
            complete(Result(TxResult("txId", 0, 0)))
        }
      }

    val server = Http().bindAndHandle(routes, address.getHostAddress, port)
  }
}
