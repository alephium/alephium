package org.alephium.appserver

import java.nio.file.Path

import scala.concurrent.{Await, ExecutionContext}
import scala.util.{Failure, Success}

import akka.actor.ActorSystem
import com.typesafe.config.Config
import com.typesafe.scalalogging.StrictLogging

import org.alephium.flow.FlowMonitor
import org.alephium.flow.setting.{AlephiumConfig, Configs, Platform}
import org.alephium.protocol.model.NetworkType
import org.alephium.util.{ActorRefT}

@SuppressWarnings(Array("org.wartremover.warts.OptionPartial"))
object Boot extends App with StrictLogging {
  val rootPath: Path                              = Platform.getRootPath()
  val networkType: Option[NetworkType]            = Configs.parseNetworkType(rootPath)
  val typesafeConfig: Config                      = Configs.parseConfigAndValidate(rootPath, networkType)
  implicit val config: AlephiumConfig             = AlephiumConfig.loadOrThrow(typesafeConfig)
  implicit val apiConfig: ApiConfig               = ApiConfig.loadOrThrow(typesafeConfig)
  implicit val system: ActorSystem                = ActorSystem("Root", typesafeConfig)
  implicit val executionContext: ExecutionContext = system.dispatcher

  val flowMonitor: ActorRefT[FlowMonitor.Command] =
    ActorRefT.build(system, FlowMonitor.props(stop()), "FlowMonitor")

  val server: Server = new ServerImpl(rootPath)

  def stop(): Unit =
    Await.result(for {
      _ <- server.stop()
      _ <- system.terminate()
    } yield (), FlowMonitor.shutdownTimeout.asScala)

  server
    .start()
    .onComplete {
      case Success(_) => ()
      case Failure(e) =>
        logger.error("Fatal error during initialization.", e)
        stop()
    }

  Runtime.getRuntime.addShutdownHook(new Thread(() => {
    stop()
  }))
}
