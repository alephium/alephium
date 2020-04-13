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
import org.alephium.crypto.{ED25519PrivateKey, ED25519PublicKey, ED25519Signature}
import org.alephium.flow.client.Miner
import org.alephium.flow.core.{BlockFlow, MultiChain, TxHandler}
import org.alephium.flow.core.FlowHandler.BlockNotify
import org.alephium.flow.io.IOResult
import org.alephium.flow.model.DataOrigin
import org.alephium.flow.network.{Bootstrapper, DiscoveryServer}
import org.alephium.flow.network.bootstrap.IntraCliqueInfo
import org.alephium.flow.platform.{Mode, PlatformConfig}
import org.alephium.protocol.config.{ConsensusConfig, GroupConfig}
import org.alephium.protocol.model.{BlockHeader, GroupIndex, Transaction, UnsignedTransaction}
import org.alephium.protocol.script.{PayTo, PubScript, Witness}
import org.alephium.rpc.model.JsonRPC._
import org.alephium.serde.deserialize
import org.alephium.util.{ActorRefT, AVector, Hex}

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

  def doCreateTransaction(req: Request): FutureTry[CreateTransactionResult] =
    Future.successful(createTransaction(mode.node.blockFlow, req))

  def doSendTransaction(req: Request): FutureTry[TransferResult] = {
    val txHandler = mode.node.allHandlers.txHandler
    sendTransaction(txHandler, req)
  }

  def doTransfer(req: Request): FutureTry[TransferResult] = {
    val txHandler = mode.node.allHandlers.txHandler
    transfer(mode.node.blockFlow, txHandler, req)
  }

  val httpRoute: Route = routeHttp(miner)
  val wsRoute: Route   = routeWs(mode.node.eventBus)

  private val httpBindingPromise: Promise[Http.ServerBinding] = Promise()
  private val wsBindingPromise: Promise[Http.ServerBinding]   = Promise()

  def runServer(): Future[Unit] = {
    for {
      httpBinding <- Http()
        .bindAndHandle(httpRoute, rpcConfig.networkInterface.getHostAddress, rpcPort)
      wsBinding <- Http()
        .bindAndHandle(wsRoute, rpcConfig.networkInterface.getHostAddress, wsPort)
    } yield {
      logger.info(s"Listening http request on $httpBinding")
      logger.info(s"Listening ws request on $wsBinding")
      httpBindingPromise.success(httpBinding)
      wsBindingPromise.success(wsBinding)
    }
  }

  def stopServer(): Future[Unit] =
    for {
      httpStop <- httpBindingPromise.future.flatMap(_.unbind)
      wsStop   <- wsBindingPromise.future.flatMap(_.unbind)
    } yield {
      logger.info(s"http unbound with message $httpStop.")
      logger.info(s"ws unbound with message $wsStop.")
      ()
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

  def withReqE[T <: RPCModel: Decoder, R <: RPCModel](req: Request)(f: T => Try[R]): Try[R] = {
    req.paramsAs[T] match {
      case Right(query)  => f(query)
      case Left(failure) => Left(failure)
    }
  }

  def withReqF[T <: RPCModel: Decoder, R <: RPCModel](req: Request)(
      f: T => FutureTry[R]): FutureTry[R] = {
    req.paramsAs[T] match {
      case Right(query)  => f(query)
      case Left(failure) => Future.successful(Left(failure))
    }
  }

  private def filterHeader(header: BlockHeader, query: FetchRequest): Boolean = {
    header.timestamp >= query.fromTs && header.timestamp <= query.toTs
  }

  def blockflowFetch(blockFlow: BlockFlow, req: Request)(
      implicit cfg: ConsensusConfig,
      fetchRequestDecoder: Decoder[FetchRequest]): Try[FetchResponse] = {
    withReqE[FetchRequest, FetchResponse](req) { query =>
      val entriesEither = for {
        headers <- blockFlow.getAllHeaders(filterHeader(_, query))
        entries <- headers.mapE(wrapBlockHeader(blockFlow, _))
      } yield entries

      entriesEither match {
        case Right(entries) => Right(FetchResponse(entries.toArray))
        case Left(_)        => failedInIO[FetchResponse]
      }
    }
  }

  def getBalance(blockFlow: BlockFlow, req: Request): Try[Balance] =
    withReqE[GetBalance, Balance](req) { query =>
      for {
        address <- decodeAddress(query.address)
        _       <- checkGroup(blockFlow, address)
        balance <- blockFlow
          .getBalance(query.`type`, address)
          .map(Balance(_))
          .left
          .flatMap(_ => failedInIO)
      } yield balance
    }

  def getGroup(blockFlow: BlockFlow, req: Request): Try[Group] =
    withReqE[GetGroup, Group](req) { query =>
      for {
        address <- decodeAddress(query.address)
      } yield {
        val groupIndex = GroupIndex.from(PayTo.PKH, address)(blockFlow.config)
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

  def decodeSignature(raw: String): Try[ED25519Signature] =
    decodeRandomBytes(raw, ED25519Signature.from, "signature")

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

  def createTransaction(blockFlow: BlockFlow, req: Request): Try[CreateTransactionResult] = {
    withReqE[CreateTransaction, CreateTransactionResult](req) { query =>
      val resultEither = for {
        fromAddress <- decodePublicKey(query.fromAddress)
        _           <- checkGroup(blockFlow, fromAddress)
        toAddress   <- decodePublicKey(query.toAddress)
        unsignedTx <- prepareUnsignedTransaction(blockFlow,
                                                 fromAddress,
                                                 query.fromType,
                                                 toAddress,
                                                 query.toType,
                                                 query.value)
      } yield {
        CreateTransactionResult.from(unsignedTx)
      }
      resultEither match {
        case Right(result) => Right(result)
        case Left(error)   => Left(error)
      }
    }
  }

  def sendTransaction(txHandler: ActorRefT[TxHandler.Command], req: Request)(
      implicit config: GroupConfig,
      askTimeout: Timeout,
      executionContext: ExecutionContext): FutureTry[TransferResult] = {
    withReqF[SendTransaction, TransferResult](req) { query =>
      val txEither = for {
        txByteString <- Hex.from(query.tx).toRight(Response.failed(s"Invalid hex"))
        unsignedTx <- deserialize[UnsignedTransaction](txByteString).left.map(serdeError =>
          Response.failed(serdeError.getMessage))
        signature <- decodeSignature(query.signature)
        publickey <- decodePublicKey(query.publicKey)
      } yield {
        val witness = Witness.build(PayTo.PKH, publickey, signature)
        Transaction.from(unsignedTx, AVector(witness))
      }
      txEither match {
        case Right(tx)   => publishTx(txHandler, tx)
        case Left(error) => Future.successful(Left(error))
      }
    }
  }

  def transfer(blockFlow: BlockFlow, txHandler: ActorRefT[TxHandler.Command], req: Request)(
      implicit config: GroupConfig,
      askTimeout: Timeout,
      executionContext: ExecutionContext): FutureTry[TransferResult] = {
    withReqF[Transfer, TransferResult](req) { query =>
      val txEither = for {
        fromAddress    <- decodePublicKey(query.fromAddress)
        _              <- checkGroup(blockFlow, fromAddress)
        toAddress      <- decodePublicKey(query.toAddress)
        fromPrivateKey <- decodePrivateKey(query.fromPrivateKey)
        tx <- prepareTransaction(blockFlow,
                                 fromAddress,
                                 query.fromType,
                                 toAddress,
                                 query.toType,
                                 query.value,
                                 fromPrivateKey)
      } yield tx
      txEither match {
        case Right(tx)   => publishTx(txHandler, tx)
        case Left(error) => Future.successful(Left(error))
      }
    }
  }

  private def publishTx(txHandler: ActorRefT[TxHandler.Command], tx: Transaction)(
      implicit config: GroupConfig,
      askTimeout: Timeout,
      executionContext: ExecutionContext): FutureTry[TransferResult] = {
    val message = TxHandler.AddTx(tx, DataOrigin.Local)
    txHandler.ask(message).mapTo[TxHandler.Event].map {
      case TxHandler.AddSucceeded =>
        Right(TransferResult(tx.hash.toHexString, tx.fromGroup.value, tx.toGroup.value))
      case TxHandler.AddFailed =>
        Left(Response.failed("Failed in adding transaction"))
    }
  }

  def prepareTransaction(blockFlow: BlockFlow,
                         fromAddress: ED25519PublicKey,
                         fromPayTo: PayTo,
                         toAddress: ED25519PublicKey,
                         toPayTo: PayTo,
                         value: BigInt,
                         fromPrivateKey: ED25519PrivateKey): Try[Transaction] = {
    blockFlow.prepareTx(fromAddress, fromPayTo, toAddress, toPayTo, value, fromPrivateKey) match {
      case Right(Some(transaction)) => Right(transaction)
      case Right(None)              => Left(Response.failed("Not enough balance"))
      case Left(_)                  => failedInIO
    }
  }

  def prepareUnsignedTransaction(blockFlow: BlockFlow,
                                 fromAddress: ED25519PublicKey,
                                 fromPayTo: PayTo,
                                 toAddress: ED25519PublicKey,
                                 toPayTo: PayTo,
                                 value: BigInt): Try[UnsignedTransaction] = {
    blockFlow.prepareUnsignedTx(fromAddress, fromPayTo, toAddress, toPayTo, value) match {
      case Right(Some(unsignedTransaction)) => Right(unsignedTransaction)
      case Right(None)                      => Left(Response.failed("Not enough balance"))
      case Left(_)                          => failedInIO
    }
  }

  def checkGroup(blockFlow: BlockFlow, address: ED25519PublicKey): Try[Unit] = {
    val pubScript  = PubScript.build(PayTo.PKH, address)
    val groupIndex = pubScript.groupIndex(blockFlow.config)
    if (blockFlow.config.brokerInfo.contains(groupIndex)) Right(())
    else Left(Response.failed(s"Address ${address.shortHex} belongs to other groups"))
  }

  def wrapBlockHeader(chain: MultiChain, header: BlockHeader)(
      implicit config: ConsensusConfig): IOResult[BlockEntry] = {
    chain.getHeight(header).map(BlockEntry.from(header, _))
  }

  def failedInIO[T]: Try[T] = Left(Response.failed("Failed in IO"))

  def blockNotifyEncode(blockNotify: BlockNotify)(implicit config: ConsensusConfig): Json =
    BlockEntry.from(blockNotify).asJson
}
