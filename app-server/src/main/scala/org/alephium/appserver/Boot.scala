package org.alephium.appserver

import scala.concurrent.{Await, ExecutionContext}
import scala.util.{Failure, Success}

import akka.actor.ActorSystem
import com.typesafe.scalalogging.StrictLogging

import org.alephium.flow.{TaskTrigger, Utils}
import org.alephium.flow.platform.PlatformConfig
import org.alephium.util.ActorRefT

object Boot extends App with StrictLogging {

  implicit val config: PlatformConfig             = PlatformConfig.loadDefault()
  implicit val system: ActorSystem                = ActorSystem("Root", config.all)
  implicit val executionContext: ExecutionContext = system.dispatcher

  val globalStopper: ActorRefT[TaskTrigger.Command] =
    ActorRefT.build(system, TaskTrigger.props(stop()), "GlobalStopper")

  val server: Server = new ServerImpl

  def stop(): Unit =
    Await.result(for {
      _ <- server.stop()
      _ <- system.terminate()
    } yield (()), Utils.shutdownTimeout.asScala)

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
