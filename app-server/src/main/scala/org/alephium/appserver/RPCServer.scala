package org.alephium.appserver

import scala.concurrent._

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.server.Route
import akka.util.{ByteString, Timeout}
import com.typesafe.scalalogging.StrictLogging
import io.circe._
import io.circe.syntax._

import org.alephium.appserver.RPCModel._
import org.alephium.crypto.{ED25519PrivateKey, ED25519PublicKey}
import org.alephium.flow.client.Miner
import org.alephium.flow.core.{BlockFlow, MultiChain, TxHandler}
import org.alephium.flow.core.FlowHandler.BlockNotify
import org.alephium.flow.model.DataOrigin
import org.alephium.flow.network.{Bootstrapper, DiscoveryServer}
import org.alephium.flow.network.bootstrap.IntraCliqueInfo
import org.alephium.flow.platform.{Mode, PlatformConfig}
import org.alephium.protocol.config.ConsensusConfig
import org.alephium.protocol.model.{BlockHeader, GroupIndex, Transaction}
import org.alephium.protocol.script.PubScript
import org.alephium.rpc.model.JsonRPC._
import org.alephium.util.{ActorRefT, Hex}

class RPCServer(mode: Mode, rpcPort: Int, wsPort: Int, miner: ActorRefT[Miner.Command])
    extends RPCServerAbstract {
  import RPCServer._
  import RPCServerAbstract.FutureTry

  implicit val system: ActorSystem                = mode.node.system
  implicit val executionContext: ExecutionContext = system.dispatcher
  implicit val config: PlatformConfig             = mode.config
  implicit val rpcConfig: RPCConfig               = RPCConfig.load(config.aleph)
  implicit val askTimeout: Timeout                = Timeout(rpcConfig.askTimeout.asScala)

  private implicit val fetchRequestDecoder: Decoder[FetchRequest] = FetchRequest.decoder

  def doBlockNotify(blockNotify: BlockNotify): Json =
    blockNotifyEncode(blockNotify)

  def doBlockflowFetch(req: Request): FutureTry[FetchResponse] =
    Future.successful(blockflowFetch(mode.node.blockFlow, req))

  def doGetNeighborCliques(req: Request): FutureTry[NeighborCliques] =
    mode.node.discoveryServer.ask(DiscoveryServer.GetNeighborCliques).mapTo[NeighborCliques].map {
      neighborCliques =>
        Right(NeighborCliques(neighborCliques.cliques))
    }

  def doGetSelfClique(req: Request): FutureTry[SelfClique] =
    mode.node.boostraper.ask(Bootstrapper.GetIntraCliqueInfo).mapTo[IntraCliqueInfo].map {
      cliqueInfo =>
        Right(SelfClique.from(cliqueInfo))
    }

  def doGetBalance(req: Request): FutureTry[Balance] =
    Future.successful(getBalance(mode.node.blockFlow, req))

  def doGetGroup(req: Request): FutureTry[Group] =
    Future.successful(getGroup(mode.node.blockFlow, req))

  def doTransfer(req: Request): FutureTry[TransferResult] = {
    val txHandler = mode.node.allHandlers.txHandler
    Future.successful(transfer(mode.node.blockFlow, txHandler, req))
  }

  val httpRoute: Route = routeHttp(miner)
  val wsRoute: Route   = routeWs(mode.node.eventBus)

  def runServer(): Future[Unit] = {
    Http()
      .bindAndHandle(httpRoute, rpcConfig.networkInterface.getHostAddress, rpcPort)
      .map(_ => ())
    Http()
      .bindAndHandle(wsRoute, rpcConfig.networkInterface.getHostAddress, wsPort)
      .map(_ => ())
  }
}

object RPCServer extends StrictLogging {
  import RPCServerAbstract._

  def apply(mode: Mode, miner: ActorRefT[Miner.Command]): RPCServer = {
    (for {
      rpcPort <- mode.config.rpcPort
      wsPort  <- mode.config.wsPort
    } yield {
      new RPCServer(mode, rpcPort, wsPort, miner)
    }) match {
      case Some(server) => server
      case None         => throw new RuntimeException("rpc and ws ports are required")
    }
  }

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

  def blockflowFetch(blockFlow: BlockFlow, req: Request)(
      implicit cfg: ConsensusConfig,
      fetchRequestDecoder: Decoder[FetchRequest]): Try[FetchResponse] = {
    withReq[FetchRequest, FetchResponse](req) { query =>
      val headers =
        blockFlow.getHeadersUnsafe(header =>
          header.timestamp >= query.fromTs && header.timestamp <= query.toTs)
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

  def getGroup(blockFlow: BlockFlow, req: Request): Try[Group] =
    withReqF[GetGroup, Group](req) { query =>
      for {
        address <- decodeAddress(query.address)
      } yield {
        val groupIndex = GroupIndex.fromP2PKH(address)(blockFlow.config)
        Group(groupIndex.value)
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

  def transfer(blockFlow: BlockFlow,
               txHandler: ActorRefT[TxHandler.Command],
               req: Request): Try[TransferResult] = {
    withReqF[Transfer, TransferResult](req) { query =>
      if (query.fromType == GetBalance.pkh || query.toType == GetBalance.pkh) {
        val resultEither = for {
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
        resultEither match {
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

  def failedInIO[T]: Try[T] = Left(Response.failed("Failed in IO"))

  def blockNotifyEncode(blockNotify: BlockNotify)(implicit config: ConsensusConfig): Json =
    BlockEntry.from(blockNotify).asJson
}
