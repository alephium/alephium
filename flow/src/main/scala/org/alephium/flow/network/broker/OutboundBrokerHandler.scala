package org.alephium.flow.network.broker

import akka.io.{IO, Tcp}

import org.alephium.flow.network.CliqueManager
import org.alephium.flow.setting.NetworkSetting
import org.alephium.protocol.message.{Hello, Payload}
import org.alephium.protocol.model.CliqueInfo
import org.alephium.util.{ActorRefT, Duration, TimeStamp}

object OutboundBrokerHandler {
  case object Retry
}

trait OutboundBrokerHandler extends BrokerHandler {
  def selfCliqueInfo: CliqueInfo

  def networkSetting: NetworkSetting

  def cliqueManager: ActorRefT[CliqueManager.Command]

  override def preStart(): Unit = {
    super.preStart()
    IO(Tcp)(context.system) ! Tcp.Connect(remoteAddress, pullMode = true)
  }

  val until: TimeStamp = TimeStamp.now() + networkSetting.retryTimeout

  var connection: ActorRefT[Tcp.Command]                                  = _
  var brokerConnectionHandler: ActorRefT[BrokerConnectionHandler.Command] = _

  override def receive: Receive = connecting

  def connecting: Receive = {
    case OutboundBrokerHandler.Retry =>
      IO(Tcp)(context.system) ! Tcp.Connect(remoteAddress, pullMode = true)

    case _: Tcp.Connected =>
      connection = ActorRefT[Tcp.Command](sender())
      brokerConnectionHandler = {
        val ref = context.actorOf(BrokerConnectionHandler.clique(remoteAddress, connection))
        context watch ref
        ref
      }
      context become handShaking

    case Tcp.CommandFailed(c: Tcp.Connect) =>
      val current = TimeStamp.now()
      if (current isBefore until) {
        scheduleOnce(self, OutboundBrokerHandler.Retry, Duration.ofSecondsUnsafe(1))
        ()
      } else {
        log.info(s"Cannot connect to ${c.remoteAddress}")
        context stop self
      }
  }

  override def handShakeDuration: Duration = networkSetting.handshakeTimeout

  override def handShakeMessage: Payload =
    Hello.unsafe(selfCliqueInfo.id, selfCliqueInfo.selfBrokerInfo)

  override def pingFrequency: Duration = networkSetting.pingFrequency
}
