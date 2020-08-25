package org.alephium.wallet.web

import java.net.InetAddress

import scala.concurrent.{ExecutionContext, Future}

import akka.http.scaladsl.model.{ContentTypes, HttpEntity, HttpMethods, HttpRequest, Uri}
import io.circe.{Codec, Encoder, Json, JsonObject}
import io.circe.generic.semiauto.deriveCodec
import io.circe.syntax._

import org.alephium.crypto.{ALFPublicKey, ALFSignature}
import org.alephium.protocol.config.GroupConfig
import org.alephium.protocol.vm.LockupScript
import org.alephium.util.Hex
import org.alephium.wallet.circe.inetAddressCodec

trait BlockFlowClient {
  def getBalance(address: String): Future[Either[String, Long]]
  def prepareTransaction(
      fromKey: String,
      toAddress: String,
      value: Long): Future[Either[String, BlockFlowClient.CreateTransactionResult]]
  def sendTransaction(tx: String,
                      signature: ALFSignature): Future[Either[String, BlockFlowClient.TxResult]]
}

object BlockFlowClient {
  def apply(httpClient: HttpClient, address: Uri, groupNum: Int)(
      implicit executionContext: ExecutionContext): BlockFlowClient =
    new Impl(httpClient, address, groupNum)

  private class Impl(httpClient: HttpClient, address: Uri, groupNum: Int)(
      implicit executionContext: ExecutionContext)
      extends BlockFlowClient {

    implicit private val groupConfig: GroupConfig = new GroupConfig { val groups = groupNum }
    private def rpcRequest[P <: JsonRpc: Encoder](uri: Uri, jsonRpc: P): HttpRequest =
      HttpRequest(
        HttpMethods.POST,
        uri = uri,
        entity = HttpEntity(
          ContentTypes.`application/json`,
          s"""{"jsonrpc":"2.0","id": 0,"method":"${jsonRpc.method}","params":${jsonRpc.asJson}}""")
      )

    @SuppressWarnings(Array("org.wartremover.warts.DefaultArguments"))
    private def request[P <: JsonRpc: Encoder, R: Codec](
        params: P,
        uri: Uri = address): Future[Either[String, R]] = {
      httpClient
        .request[Result[R]](
          rpcRequest(uri, params)
        )
        .map(_.map(_.result))
    }

    def getBalance(address: String): Future[Either[String, Long]] =
      LockupScript.fromBase58(address) match {
        case None => Future.successful(Left(s"Cannot decode $address"))
        case Some(lockupScript) =>
          val fromGroup = lockupScript.groupIndex.value
          getSelfClique().flatMap {
            case Left(error) => Future.successful(Left(error))
            case Right(selfClique) =>
              selfClique
                .index(fromGroup) match {
                case Left(error) => Future.successful(Left(error))
                case Right(index) =>
                  selfClique.peers
                    .lift(index)
                    .flatMap(peer => peer.rpcPort.map(rpcPort => (peer.address, rpcPort))) match {
                    case None =>
                      Future.successful(
                        Left(s"cannot find peer for group $fromGroup (peers: ${selfClique.peers})"))
                    case Some((peerAddress, rpcPort)) =>
                      val uri = Uri(s"http://${peerAddress.getHostAddress}:${rpcPort}")
                      request[GetBalance, Balance](GetBalance(address), uri).map(_.map(_.balance))
                  }
              }
          }
      }

    def prepareTransaction(fromKey: String,
                           toAddress: String,
                           value: Long): Future[Either[String, CreateTransactionResult]] = {
      Hex.from(fromKey).flatMap(ALFPublicKey.from).map(LockupScript.p2pkh) match {
        case None => Future.successful(Left(s"Cannot decode $address"))
        case Some(lockupScript) =>
          val fromGroup = lockupScript.groupIndex.value
          getSelfClique().flatMap {
            case Left(error) => Future.successful(Left(error))
            case Right(selfClique) =>
              selfClique
                .index(fromGroup) match {
                case Left(error) => Future.successful(Left(error))
                case Right(index) =>
                  selfClique.peers
                    .lift(index)
                    .flatMap(peer => peer.rpcPort.map(rpcPort => (peer.address, rpcPort))) match {
                    case None =>
                      Future.successful(
                        Left(s"cannot find peer for group $fromGroup (peers: ${selfClique.peers})"))
                    case Some((peerAddress, rpcPort)) =>
                      val uri = Uri(s"http://${peerAddress.getHostAddress}:${rpcPort}")
                      request[CreateTransaction, CreateTransactionResult](
                        CreateTransaction(fromKey, toAddress, value),
                        uri)
                  }
              }
          }
      }
    }

    def sendTransaction(
        tx: String,
        signature: ALFSignature): Future[Either[String, BlockFlowClient.TxResult]] = {
      request[SendTransaction, TxResult](SendTransaction(tx, signature.toHexString), address)
    }

    private def getSelfClique(): Future[Either[String, SelfClique]] =
      request[GetSelfClique.type, SelfClique](
        GetSelfClique
      )
  }

  final case class Result[A: Codec](result: A)
  object Result {
    implicit def codec[A: Codec]: Codec[Result[A]] = deriveCodec[Result[A]]
  }

  sealed trait JsonRpc {
    def method: String
  }

  final case object GetSelfClique extends JsonRpc {
    val method: String = "self_clique"
    implicit val encoder: Encoder[GetSelfClique.type] = new Encoder[GetSelfClique.type] {
      final def apply(selfClique: GetSelfClique.type): Json = JsonObject.empty.asJson
    }
  }

  final case class GetBalance(address: String) extends JsonRpc {
    val method: String = "get_balance"
  }
  object GetBalance {
    implicit val codec: Codec[GetBalance] = deriveCodec[GetBalance]
  }

  final case class Balance(balance: Long, utxoNum: Int)
  object Balance {
    implicit val codec: Codec[Balance] = deriveCodec[Balance]
  }

  final case class CreateTransaction(
      fromKey: String,
      toAddress: String,
      value: Long
  ) extends JsonRpc {
    val method: String = "create_transaction"
  }
  object CreateTransaction {
    implicit val codec: Codec[CreateTransaction] = deriveCodec[CreateTransaction]
  }

  final case class CreateTransactionResult(unsignedTx: String, hash: String)
  object CreateTransactionResult {
    implicit val codec: Codec[CreateTransactionResult] = deriveCodec[CreateTransactionResult]
  }

  final case class SendTransaction(tx: String, signature: String) extends JsonRpc {
    val method: String = "send_transaction"
  }
  object SendTransaction {
    implicit val codec: Codec[SendTransaction] = deriveCodec[SendTransaction]
  }

  final case class TxResult(txId: String, fromGroup: Int, toGroup: Int)
  object TxResult {
    implicit val codec: Codec[TxResult] = deriveCodec[TxResult]
  }
  final case class PeerAddress(address: InetAddress, rpcPort: Option[Int], wsPort: Option[Int])
  object PeerAddress {
    implicit val codec: Codec[PeerAddress] = deriveCodec[PeerAddress]
  }

  final case class SelfClique(peers: Seq[PeerAddress], groupNumPerBroker: Int) {
    def index(group: Int): Either[String, Int] =
      if (groupNumPerBroker <= 0) {
        Left(s"SelfClique.groupNumPerBroker ($groupNumPerBroker) cannot be less or equal to zero")
      } else {
        Right(group / groupNumPerBroker)
      }
  }
  object SelfClique {
    implicit val codec: Codec[SelfClique] = deriveCodec[SelfClique]
  }
}
