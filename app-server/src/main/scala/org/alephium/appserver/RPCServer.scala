package org.alephium.appserver

import scala.concurrent._

import akka.actor.{ActorRef, ActorSystem}
import akka.http.scaladsl.Http
import akka.pattern.ask
import akka.stream.ActorMaterializer
import akka.util.{ByteString, Timeout}
import com.typesafe.scalalogging.StrictLogging
import io.circe._
import io.circe.syntax._

import org.alephium.appserver.RPCModel._
import org.alephium.crypto.{ED25519PrivateKey, ED25519PublicKey}
import org.alephium.flow.client.FairMiner
import org.alephium.flow.core.{BlockFlow, MultiChain, TxHandler}
import org.alephium.flow.core.FlowHandler.BlockNotify
import org.alephium.flow.model.DataOrigin
import org.alephium.flow.network.DiscoveryServer
import org.alephium.flow.platform.{Mode, PlatformProfile}
import org.alephium.protocol.config.ConsensusConfig
import org.alephium.protocol.model.{BlockHeader, CliqueInfo, GroupIndex, Transaction}
import org.alephium.protocol.script.PubScript
import org.alephium.rpc.model.JsonRPC._
import org.alephium.util.{Hex, TimeStamp}

class RPCServer(mode: Mode) extends RPCServerAbstract {
  import RPCServer._

  implicit val system: ActorSystem                = mode.node.system
  implicit val materializer: ActorMaterializer    = ActorMaterializer()
  implicit val executionContext: ExecutionContext = system.dispatcher
  implicit val config: PlatformProfile            = mode.profile
  implicit val rpcConfig: RPCConfig               = RPCConfig.load(config.aleph)
  implicit val askTimeout: Timeout                = Timeout(rpcConfig.askTimeout.asScala)

  def doBlockflowFetch(req: Request): FutureTry[FetchResponse] =
    Future.successful(blockflowFetch(mode.node.blockFlow, req))

  @SuppressWarnings(Array("org.wartremover.warts.AsInstanceOf"))
  def doGetPeerCliques(req: Request): FutureTry[PeerCliques] =
    mode.node.discoveryServer.ask(DiscoveryServer.GetPeerCliques).map { result =>
      val peers = result.asInstanceOf[DiscoveryServer.PeerCliques].peers
      Right(PeerCliques(peers))
    }

  def doBlockNotify(blockNotify: BlockNotify): Json =
    blockNotifyEncode(blockNotify)

  @SuppressWarnings(Array("org.wartremover.warts.AsInstanceOf"))
  def doGetSelfClique(req: Request): FutureTry[SelfClique] =
    mode.node.discoveryServer.ask(DiscoveryServer.GetSelfClique).map { result =>
      val cliqueInfo = result.asInstanceOf[CliqueInfo]
      Right(SelfClique.from(cliqueInfo))
    }

  def doGetBalance(req: Request): FutureTry[Balance] =
    Future.successful(getBalance(mode.node.blockFlow, req))

  def doTransfer(req: Request): FutureTry[TransferResult] = {
    val txHandler = mode.node.allHandlers.txHandler
    Future.successful(transfer(mode.node.blockFlow, txHandler, req))
  }

  def runServer(): Future[Unit] = {
    val miner = {
      val props = FairMiner.props(mode.node).withDispatcher("akka.actor.mining-dispatcher")
      system.actorOf(props, s"FairMiner")
    }

    Http()
      .bindAndHandle(routeHttp(miner), rpcConfig.networkInterface.getHostAddress, mode.rpcHttpPort)
      .map(_ => ())
    Http()
      .bindAndHandle(routeWs(mode.node.eventBus),
                     rpcConfig.networkInterface.getHostAddress,
                     mode.rpcWsPort)
      .map(_ => ())
  }
}

object RPCServer extends StrictLogging {
  import Response.Failure
  type Try[T]       = Either[Failure, T]
  type FutureTry[T] = Future[Try[T]]

  val bufferSize: Int = 64

  def withReq[T: Decoder, R](req: Request)(f: T => R): Try[R] = {
    req.paramsAs[T] match {
      case Right(query)  => Right(f(query))
      case Left(failure) => Left(failure)
    }
  }

  def withReqF[T <: RPCModel: Decoder, R <: RPCModel](req: Request)(f: T => Try[R]): Try[R] = {
    req.paramsAs[T] match {
      case Right(query)  => f(query)
      case Left(failure) => Left(failure)
    }
  }

  @SuppressWarnings(Array("org.wartremover.warts.OptionPartial"))
  def blockflowFetch(blockFlow: BlockFlow, req: Request)(
      implicit rpc: RPCConfig,
      cfg: ConsensusConfig): Try[FetchResponse] = {
    withReq[FetchRequest, FetchResponse](req) { query =>
      val now        = TimeStamp.now()
      val lowerBound = (now - rpc.blockflowFetchMaxAge).get // Note: get should be safe
      val from = query.from match {
        case Some(ts) => if (ts > lowerBound) ts else lowerBound
        case None     => lowerBound
      }

      val headers = blockFlow.getHeadersUnsafe(header => header.timestamp > from)
      FetchResponse(headers.map(wrapBlockHeader(blockFlow, _)))
    }
  }

  def getBalance(blockFlow: BlockFlow, req: Request): Try[Balance] = {
    withReqF[GetBalance, Balance](req) { query =>
      if (query.`type` == GetBalance.pkh) {
        val result = for {
          address <- decodeAddress(query.address)
          _       <- checkGroup(blockFlow, address)
          balance <- getP2pkhBalance(blockFlow, address)
        } yield balance
        result match {
          case Right(balance) => Right(balance)
          case Left(error)    => Left(error)
        }
      } else {
        Left(Response.failed(s"Invalid address type ${query.`type`}"))
      }
    }
  }

  def decodeAddress(raw: String): Try[ED25519PublicKey] = {
    val addressOpt = for {
      bytes   <- Hex.from(raw)
      address <- ED25519PublicKey.from(bytes)
    } yield address

    addressOpt match {
      case Some(address) => Right(address)
      case None          => Left(Response.failed("Failed in decoding address"))
    }
  }

  def decodePublicKey(raw: String): Try[ED25519PublicKey] =
    decodeRandomBytes(raw, ED25519PublicKey.from, "public key")

  def decodePrivateKey(raw: String): Try[ED25519PrivateKey] =
    decodeRandomBytes(raw, ED25519PrivateKey.from, "private key")

  def decodeRandomBytes[T](raw: String, from: ByteString => Option[T], name: String): Try[T] = {
    val addressOpt = for {
      bytes   <- Hex.from(raw)
      address <- from(bytes)
    } yield address

    addressOpt match {
      case Some(address) => Right(address)
      case None          => Left(Response.failed(s"Failed in decoding $name"))
    }
  }

  def getP2pkhBalance(blockFlow: BlockFlow, address: ED25519PublicKey): Try[Balance] = {
    blockFlow.getP2pkhUtxos(address) match {
      case Right(utxos) => Right(Balance(utxos.sumBy(_._2.value), utxos.length))
      case Left(_)      => failedInIO
    }
  }

  def transfer(blockFlow: BlockFlow, txHandler: ActorRef, req: Request): Try[TransferResult] = {
    withReqF[Transfer, TransferResult](req) { query =>
      if (query.fromType == GetBalance.pkh || query.toType == GetBalance.pkh) {
        val result = for {
          fromAddress    <- decodePublicKey(query.fromAddress)
          _              <- checkGroup(blockFlow, fromAddress)
          toAddress      <- decodePublicKey(query.toAddress)
          fromPrivateKey <- decodePrivateKey(query.fromPrivateKey)
          tx             <- prepareTransaction(blockFlow, fromAddress, toAddress, query.value, fromPrivateKey)
        } yield {
          // publish transaction
          txHandler ! TxHandler.AddTx(tx, DataOrigin.Local)
          TransferResult(Hex.toHexString(tx.hash.bytes))
        }
        result match {
          case Right(result) => Right(result)
          case Left(error)   => Left(error)
        }
      } else {
        Left(Response.failed(s"Invalid address types: ${query.fromType} or ${query.toType}"))
      }
    }
  }

  def prepareTransaction(blockFlow: BlockFlow,
                         fromAddress: ED25519PublicKey,
                         toAddress: ED25519PublicKey,
                         value: BigInt,
                         fromPrivateKey: ED25519PrivateKey): Try[Transaction] = {
    blockFlow.prepareP2pkhTx(fromAddress, toAddress, value, fromPrivateKey) match {
      case Right(Some(transaction)) => Right(transaction)
      case Right(None)              => Left(Response.failed("Not enough balance"))
      case Left(_)                  => failedInIO
    }
  }

  def checkGroup(blockFlow: BlockFlow, address: ED25519PublicKey): Try[Unit] = {
    val pubScript  = PubScript.p2pkh(address)
    val groupIndex = GroupIndex.from(pubScript)(blockFlow.config)
    if (blockFlow.config.brokerInfo.contains(groupIndex)) Right(())
    else Left(Response.failed(s"Address ${address.shortHex} belongs to other groups"))
  }

  def wrapBlockHeader(chain: MultiChain, header: BlockHeader)(
      implicit config: ConsensusConfig): BlockEntry = {
    BlockEntry.from(header, chain.getHeight(header))
  }

  def execute(f: => Unit)(implicit ec: ExecutionContext): FutureTry[Boolean] =
    Future {
      f
      Right(true)
    }

  def wrap[T <: RPCModel: Encoder](req: Request, result: FutureTry[T])(
      implicit ec: ExecutionContext): Future[Response] = result.map {
    case Right(t)    => Response.successful(req, t)
    case Left(error) => error
  }

  // Note: use wrap when T derives RPCModel
  def simpleWrap[T: Encoder](req: Request, result: FutureTry[T])(
      implicit ec: ExecutionContext): Future[Response] = result.map {
    case Right(t)    => Response.successful(req, t)
    case Left(error) => error
  }

  def failedInIO[T]: Try[T] = Left(Response.failed("Failed in IO"))

  def blockNotifyEncode(blockNotify: BlockNotify)(implicit config: ConsensusConfig): Json =
    BlockEntry.from(blockNotify).asJson
}
