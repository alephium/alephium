package org.alephium.flow.network.broker

import org.alephium.util.BaseActor

object MisBehavingHandler {
  sealed trait Command

  sealed trait MisBehavior extends Command {
    def peer: BrokerHandler.ConnectionInfo
  }
  sealed trait Critical  extends MisBehavior
  sealed trait Error     extends MisBehavior
  sealed trait Warning   extends MisBehavior
  sealed trait Uncertain extends MisBehavior

  final case class InvalidPingPong(peer: BrokerHandler.ConnectionInfo) extends Critical
  final case class Spamming(peer: BrokerHandler.ConnectionInfo)        extends Error
  final case class RequestTimeout(peer: BrokerHandler.ConnectionInfo)  extends Uncertain
}

trait MisBehavingHandler extends BaseActor {
  override def receive: Receive = {
//    case critical: Critical   => ???
//    case error: Error         => ???
//    case warning: Warning     => ???
//    case uncertain: Uncertain => ???
    case x =>
      log.debug(s"Received $x")
  }
}
