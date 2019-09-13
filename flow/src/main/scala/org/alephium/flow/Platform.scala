package org.alephium.flow

import java.nio.file.Path

import scala.concurrent.Future

import com.typesafe.scalalogging.StrictLogging

import org.alephium.util.{Env, Files}

trait Platform extends App with StrictLogging {
  def mode: Mode

  def init(): Future[Unit] = {
    runServer()
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
