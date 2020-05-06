package org.alephium.flow

import scala.concurrent.Future

trait Stoppable {
  def stop(): Future[Unit]
}
