package org.alephium.wallet

import scala.concurrent.{Await, ExecutionContext, Future, Promise}

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.server.Route
import akka.stream.OverflowStrategy
import com.typesafe.config.{Config, ConfigFactory}
import com.typesafe.scalalogging.StrictLogging
import pureconfig.ConfigSource
import pureconfig.generic.auto._

import org.alephium.util.Duration
import org.alephium.wallet.config.WalletConfig
import org.alephium.wallet.service.WalletService
import org.alephium.wallet.web._

// scalastyle:off magic.number
class WalletApp(config: WalletConfig)(implicit actorSystem: ActorSystem,
                                      executionContext: ExecutionContext)
    extends StrictLogging {

  val httpClient: HttpClient = HttpClient(512, OverflowStrategy.fail)

  val blockFlowClient: BlockFlowClient =
    BlockFlowClient.apply(httpClient, config.blockflow.uri, config.blockflow.groups)

  val walletService: WalletService = WalletService.apply(blockFlowClient, config.secretDir)

  val walletServer: WalletServer = new WalletServer(walletService)

  val routes: Route = walletServer.route

  private val bindingPromise: Promise[Http.ServerBinding] = Promise()

  for {
    binding <- Http().bindAndHandle(routes, "localhost", config.port)
  } yield {
    bindingPromise.success(binding)
    logger.info(s"Listening wallet http request on $binding")
  }

  def stop(): Future[Unit] =
    for {
      _ <- bindingPromise.future.flatMap(_.unbind())
    } yield {
      logger.info("Application stopped")
    }
}

@SuppressWarnings(Array("org.wartremover.warts.OptionPartial"))
object Main extends App {
  implicit val system: ActorSystem                = ActorSystem("wallet-app")
  implicit val executionContext: ExecutionContext = system.dispatcher

  val typesafeConfig: Config = ConfigFactory.load().getConfig("wallet")

  val walletConfig: WalletConfig =
    ConfigSource.fromConfig(typesafeConfig).load[WalletConfig].toOption.get

  val walletApp: WalletApp = new WalletApp(walletConfig)
  scala.sys.addShutdownHook(Await.result(walletApp.stop(), Duration.ofSecondsUnsafe(10).asScala))
}
// scalastyle:on magic.number
