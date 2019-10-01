package org.alephium.flow.platform

import java.nio.file.Path

import scala.concurrent.Future
import scala.util.{Failure, Success}

import com.typesafe.scalalogging.StrictLogging

import org.alephium.util.{Env, Files}

trait Platform extends App with StrictLogging {
  def mode: Mode

  def init(): Future[Unit] = {
    val future = runServer()

    future.onComplete {
      case Success(_) => ()
      case Failure(e) => logger.error("Fatal error during initialization.", e)
    } (scala.concurrent.ExecutionContext.global)

    future
  }

  def runServer(): Future[Unit]
}

object Platform {
  def getRootPath(env: Env): Path = {
    env match {
      case Env.Prod =>
        Files.homeDir.resolve(".alephium")
      case Env.Debug =>
        Files.homeDir.resolve(s".alephium-${env.name}")
      case Env.Test =>
        Files.tmpDir.resolve(s".alephium-${env.name}")
    }
  }
}
