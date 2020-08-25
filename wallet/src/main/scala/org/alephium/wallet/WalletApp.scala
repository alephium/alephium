package org.alephium.wallet

import java.nio.file.{Path, Paths}

import scala.concurrent.{Await, ExecutionContext, Future, Promise}

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.Uri
import akka.http.scaladsl.server.Route
import akka.stream.OverflowStrategy
import com.typesafe.config.{Config, ConfigFactory}
import com.typesafe.scalalogging.StrictLogging

import org.alephium.util.Duration
import org.alephium.wallet.service.WalletService
import org.alephium.wallet.web._

// scalastyle:off magic.number
class WalletApp(walletPort: Int, blockFlowUri: Uri, groupNum: Int, secretDir: Path)(
    implicit actorSystem: ActorSystem,
    executionContext: ExecutionContext)
    extends StrictLogging {

  val httpClient: HttpClient = HttpClient(512, OverflowStrategy.fail)

  val blockFlowClient: BlockFlowClient = BlockFlowClient.apply(httpClient, blockFlowUri, groupNum)

  val walletService: WalletService = WalletService.apply(blockFlowClient, secretDir)

  val walletServer: WalletServer = new WalletServer(walletService)

  val routes: Route = walletServer.route

  private val bindingPromise: Promise[Http.ServerBinding] = Promise()

  for {
    binding <- Http().bindAndHandle(routes, "localhost", walletPort)
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

object Main extends App {
  implicit val system: ActorSystem                = ActorSystem("wallet-app")
  implicit val executionContext: ExecutionContext = system.dispatcher

  val config: Config = ConfigFactory.load()

  val blockflowUri: Uri = {
    val host = config.getString("blockflow.host")
    val port = config.getInt("blockflow.port")
    Uri(s"http://$host:$port")
  }

  val groupNum: Int = config.getInt("blockflow.groupNum")

  val walletPort: Int = config.getInt("wallet.port")

  val walletSecretDir: Path = Paths.get(config.getString("wallet.secretDir"))

  val walletApp: WalletApp = new WalletApp(walletPort, blockflowUri, groupNum, walletSecretDir)

  scala.sys.addShutdownHook(Await.result(walletApp.stop(), Duration.ofSecondsUnsafe(10).asScala))
}
// scalastyle:on magic.number
