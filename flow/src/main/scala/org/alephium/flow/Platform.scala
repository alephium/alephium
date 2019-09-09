package org.alephium.flow

import scala.concurrent.Future

import com.typesafe.scalalogging.StrictLogging

trait Platform extends App with StrictLogging {
  def mode: Mode

  def init(): Future[Unit] = {
    runServer()
  }

  def runServer(): Future[Unit]
}
