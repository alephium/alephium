package org.alephium.wallet

import scala.collection.immutable.ArraySeq
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.{Failure, Success}

import akka.actor.ActorSystem
import com.typesafe.config.{Config, ConfigFactory}
import com.typesafe.scalalogging.StrictLogging
import pureconfig.ConfigSource
import pureconfig.generic.auto._

import org.alephium.util.{Duration, Service}
import org.alephium.wallet.config.WalletConfig

object Main extends App with Service with StrictLogging {
  implicit val system: ActorSystem                = ActorSystem("wallet-app")
  implicit val executionContext: ExecutionContext = system.dispatcher

  val typesafeConfig: Config = ConfigFactory.load().getConfig("wallet")

  val walletConfig: WalletConfig =
    ConfigSource
      .fromConfig(typesafeConfig)
      .load[WalletConfig]
      .getOrElse(throw new RuntimeException(s"Cannot load wallet config"))

  val walletApp: WalletApp = new WalletApp(walletConfig)

  override def subServices: ArraySeq[Service] = ArraySeq(walletApp)

  override protected def startSelfOnce(): Future[Unit] =
    Future.successful(())

  override protected def stopSelfOnce(): Future[Unit] = {
    Future.successful(())
  }

  scala.sys.addShutdownHook(Await.result(walletApp.stop(), Duration.ofSecondsUnsafe(10).asScala))

  start()
    .onComplete {
      case Success(_) => ()
      case Failure(e) =>
        logger.error("Fatal error during initialization.", e)
        stop()
    }
}
