package org.alephium.wallet.web

import java.net.InetAddress

import scala.concurrent.{ExecutionContext, Future}

import akka.http.scaladsl.model.{ContentTypes, HttpEntity, HttpMethods, HttpRequest, Uri}
import io.circe.{Codec, Encoder, Json, JsonObject}
import io.circe.generic.semiauto.deriveCodec
import io.circe.syntax._

import org.alephium.protocol.{PublicKey, Signature}
import org.alephium.protocol.config.GroupConfig
import org.alephium.protocol.model.{Address, GroupIndex, NetworkType}
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
                      signature: Signature,
                      fromGroup: Int): Future[Either[String, BlockFlowClient.TxResult]]
}

object BlockFlowClient {
  def apply(httpClient: HttpClient, address: Uri, groupNum: Int, networkType: NetworkType)(
      implicit executionContext: ExecutionContext): BlockFlowClient =
    new Impl(httpClient, address, groupNum, networkType)

  private class Impl(httpClient: HttpClient, address: Uri, groupNum: Int, networkType: NetworkType)(
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

    private def request[P <: JsonRpc: Encoder, R: Codec](params: P,
                                                         uri: Uri): Future[Either[String, R]] = {
      httpClient
        .request[Result[R]](
          rpcRequest(uri, params)
        )
        .map(_.map(_.result))
    }

    private def requestFromGroup[P <: JsonRpc: Encoder, R: Codec](
        fromGroup: GroupIndex,
        params: P
    ): Future[Either[String, R]] =
      uriFromGroup(fromGroup).flatMap {
        _.fold(
          error => Future.successful(Left(error)),
          uri   => request[P, R](params, uri)
        )
      }

    private def uriFromGroup(fromGroup: GroupIndex): Future[Either[String, Uri]] =
      getSelfClique().map { selfCliqueEither =>
        for {
          selfClique <- selfCliqueEither
          index      <- selfClique.index(fromGroup.value)
          peerPort <- selfClique.peers
            .lift(index)
            .flatMap(peer => peer.rpcPort.map(rpcPort => (peer.address, rpcPort)))
            .toRight(s"cannot find peer for group ${fromGroup.value} (peers: ${selfClique.peers})")
        } yield {
          val (peerAddress, rpcPort) = peerPort
          Uri(s"http://${peerAddress.getHostAddress}:${rpcPort}")
        }
      }

    def getBalance(address: String): Future[Either[String, Long]] =
      Address.fromBase58(address, networkType) match {
        case None => Future.successful(Left(s"Cannot decode $address"))
        case Some(lockupScript) =>
          requestFromGroup[GetBalance, Balance](
            lockupScript.groupIndex,
            GetBalance(address)
          ).map(_.map(_.balance))
      }

    def prepareTransaction(fromKey: String,
                           toAddress: String,
                           value: Long): Future[Either[String, CreateTransactionResult]] = {
      Hex.from(fromKey).flatMap(PublicKey.from).map(LockupScript.p2pkh) match {
        case None => Future.successful(Left(s"Cannot decode key $fromKey"))
        case Some(lockupScript) =>
          requestFromGroup[CreateTransaction, CreateTransactionResult](
            lockupScript.groupIndex,
            CreateTransaction(fromKey, toAddress, value)
          )
      }
    }

    def sendTransaction(tx: String,
                        signature: Signature,
                        fromGroup: Int): Future[Either[String, BlockFlowClient.TxResult]] = {
      requestFromGroup[SendTransaction, TxResult](
        GroupIndex.unsafe(fromGroup),
        SendTransaction(tx, signature.toHexString)
      )
    }

    private def getSelfClique(): Future[Either[String, SelfClique]] =
      request[GetSelfClique.type, SelfClique](
        GetSelfClique,
        address
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

  final case class CreateTransactionResult(unsignedTx: String,
                                           hash: String,
                                           fromGroup: Int,
                                           toGroup: Int)
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
