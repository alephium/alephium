package org.alephium.appserver

import scala.concurrent._

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.server.Route
import akka.util.{ByteString, Timeout}
import io.circe._
import io.circe.syntax._

import org.alephium.appserver.RPCModel._
import org.alephium.crypto.{ED25519PrivateKey, ED25519PublicKey, ED25519Signature}
import org.alephium.flow.Stoppable
import org.alephium.flow.client.Miner
import org.alephium.flow.core.{BlockFlow, TxHandler}
import org.alephium.flow.core.FlowHandler.BlockNotify
import org.alephium.flow.model.DataOrigin
import org.alephium.flow.network.{Bootstrapper, CliqueManager, DiscoveryServer, InterCliqueManager}
import org.alephium.flow.network.bootstrap.IntraCliqueInfo
import org.alephium.flow.platform.{Mode, PlatformConfig}
import org.alephium.protocol.config.{ConsensusConfig, GroupConfig}
import org.alephium.protocol.model.{GroupIndex, Transaction, UnsignedTransaction}
import org.alephium.protocol.script.{PayTo, PubScript}
import org.alephium.rpc.model.JsonRPC._
import org.alephium.serde.deserialize
import org.alephium.util.{ActorRefT, AVector, Duration, Hex, U64}

class RPCServer(mode: Mode, rpcPort: Int, wsPort: Int, miner: ActorRefT[Miner.Command])(
    implicit val system: ActorSystem,
    val config: PlatformConfig,
    val executionContext: ExecutionContext)
    extends RPCServerAbstract
    with Stoppable {
  import RPCServer._
  import RPCServerAbstract.FutureTry

  implicit val rpcConfig: RPCConfig = RPCConfig.load(config.aleph)
  implicit val askTimeout: Timeout  = Timeout(rpcConfig.askTimeout.asScala)

  private val terminationHardDeadline = Duration.ofSecondsUnsafe(10).asScala

  private implicit val fetchRequestDecoder: Decoder[FetchRequest] = FetchRequest.decoder

  def doBlockNotify(blockNotify: BlockNotify): Json =
    blockNotifyEncode(blockNotify)

  def doBlockflowFetch(req: Request): FutureTry[FetchResponse] =
    Future.successful(blockflowFetch(mode.node.blockFlow, req))

  def doGetNeighborCliques(req: Request): FutureTry[NeighborCliques] =
    mode.node.discoveryServer
      .ask(DiscoveryServer.GetNeighborCliques)
      .mapTo[DiscoveryServer.NeighborCliques]
      .map { neighborCliques =>
        Right(NeighborCliques(neighborCliques.peers))
      }

  def doGetSelfClique(req: Request): FutureTry[SelfClique] =
    mode.node.boostraper.ask(Bootstrapper.GetIntraCliqueInfo).mapTo[IntraCliqueInfo].map {
      cliqueInfo =>
        Right(SelfClique.from(cliqueInfo))
    }

  def doGetSelfCliqueSynced(req: Request): FutureTry[Boolean] =
    mode.node.cliqueManager.ask(CliqueManager.IsSelfCliqueSynced).mapTo[Boolean].map(Right(_))

  def doGetInterCliquePeerInfo(req: Request): FutureTry[Seq[InterCliquePeerInfo]] =
    mode.node.cliqueManager
      .ask(InterCliqueManager.GetSyncStatuses)
      .mapTo[Seq[InterCliqueManager.SyncStatus]]
      .map { syncedStatuses =>
        Right(syncedStatuses.map(InterCliquePeerInfo.from))
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

  private var started: Boolean                                = false
  private val httpBindingPromise: Promise[Http.ServerBinding] = Promise()
  private val wsBindingPromise: Promise[Http.ServerBinding]   = Promise()

  def runServer(): Future[Unit] = {
    started = true
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

  def stop(): Future[Unit] =
    if (started) {
      for {
        httpStop <- httpBindingPromise.future.flatMap(
          _.terminate(hardDeadline = terminationHardDeadline))
        wsStop <- wsBindingPromise.future.flatMap(
          _.terminate(hardDeadline = terminationHardDeadline))
      } yield {
        logger.info(s"http unbound with message $httpStop.")
        logger.info(s"ws unbound with message $wsStop.")
        ()
      }
    } else {
      Future.successful(())
    }
}

object RPCServer extends {
  import RPCServerAbstract._

  def apply(mode: Mode, miner: ActorRefT[Miner.Command])(
      implicit system: ActorSystem,
      config: PlatformConfig,
      executionContext: ExecutionContext): RPCServer = {
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

  def blockflowFetch(blockFlow: BlockFlow, req: Request)(
      implicit cfg: ConsensusConfig,
      fetchRequestDecoder: Decoder[FetchRequest]): Try[FetchResponse] = {
    withReqE[FetchRequest, FetchResponse](req) { query =>
      val entriesEither = for {
        headers <- blockFlow.getHeightedBlockHeaders(query.fromTs, query.toTs)
      } yield headers.map { case (header, height) => BlockEntry.from(header, height) }

      entriesEither match {
        case Right(entries) => Right(FetchResponse(entries.toArray.toIndexedSeq))
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
      } yield {
        Transaction.from(unsignedTx, AVector.fill(unsignedTx.inputs.length)(signature))
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
      case _: TxHandler.AddSucceeded =>
        Right(TransferResult(tx.hash.toHexString, tx.fromGroup.value, tx.toGroup.value))
      case _: TxHandler.AddFailed =>
        Left(Response.failed("Failed in adding transaction"))
    }
  }

  def prepareTransaction(blockFlow: BlockFlow,
                         fromAddress: ED25519PublicKey,
                         fromPayTo: PayTo,
                         toAddress: ED25519PublicKey,
                         toPayTo: PayTo,
                         value: U64,
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
                                 value: U64): Try[UnsignedTransaction] = {
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

  def failedInIO[T]: Try[T] = Left(Response.failed("Failed in IO"))

  def blockNotifyEncode(blockNotify: BlockNotify)(implicit config: ConsensusConfig): Json =
    BlockEntry.from(blockNotify).asJson
}
