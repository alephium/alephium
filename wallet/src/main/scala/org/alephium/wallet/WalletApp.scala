package org.alephium.wallet

import scala.collection.immutable.ArraySeq
import scala.concurrent.{ExecutionContext, Future, Promise}

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.server.Route
import akka.stream.OverflowStrategy
import com.typesafe.scalalogging.StrictLogging

import org.alephium.util.Service
import org.alephium.wallet.config.WalletConfig
import org.alephium.wallet.service.WalletService
import org.alephium.wallet.web._

class WalletApp(config: WalletConfig)(implicit actorSystem: ActorSystem,
                                      val executionContext: ExecutionContext)
    extends Service
    with StrictLogging {

  // scalastyle:off magic.number
  val httpClient: HttpClient = HttpClient(512, OverflowStrategy.fail)
  // scalastyle:on magic.number

  val blockFlowClient: BlockFlowClient =
    BlockFlowClient.apply(httpClient,
                          config.blockflow.uri,
                          config.blockflow.groups,
                          config.networkType)

  val walletService: WalletService =
    WalletService.apply(blockFlowClient, config.secretDir, config.networkType)

  val walletServer: WalletServer = new WalletServer(walletService, config.networkType)

  val routes: Route = walletServer.route

  private val bindingPromise: Promise[Http.ServerBinding] = Promise()

  override val subServices: ArraySeq[Service] = ArraySeq(walletService)

  protected def startSelfOnce(): Future[Unit] = {
    for {
      binding <- Http().bindAndHandle(routes, "localhost", config.port)
    } yield {
      bindingPromise.success(binding)
      logger.info(s"Listening wallet http request on $binding")
    }
  }

  protected def stopSelfOnce(): Future[Unit] =
    for {
      _ <- bindingPromise.future.flatMap(_.unbind())
    } yield {
      logger.info("Wallet stopped")
    }
}
