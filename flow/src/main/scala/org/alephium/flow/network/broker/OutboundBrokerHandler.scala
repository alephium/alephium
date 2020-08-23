package org.alephium.flow.network.broker

import java.net.InetSocketAddress

import akka.actor.Props
import akka.io.{IO, Tcp}

import org.alephium.flow.core.BlockFlow
import org.alephium.flow.handler.AllHandlers
import org.alephium.flow.network.CliqueManager
import org.alephium.flow.setting.NetworkSetting
import org.alephium.protocol.config.BrokerConfig
import org.alephium.protocol.message.{Hello, Payload}
import org.alephium.protocol.model.{BrokerInfo, CliqueId, CliqueInfo}
import org.alephium.util.{ActorRefT, Duration, TimeStamp}

object OutboundBrokerHandler {
  // scalastyle:off parameter.number
  def props(selfCliqueInfo: CliqueInfo,
            remoteBroker: BrokerInfo,
            blockflow: BlockFlow,
            allHandlers: AllHandlers,
            cliqueManager: ActorRefT[CliqueManager.Command],
            brokerManager: ActorRefT[BrokerManager.Command],
            blockFlowSynchronizer: ActorRefT[BlockFlowSynchronizer.Command])(
      implicit brokerConfig: BrokerConfig,
      networkSetting: NetworkSetting): Props =
    Props(
      new OutboundBrokerHandler(selfCliqueInfo,
                                remoteBroker.address,
                                blockflow,
                                allHandlers,
                                cliqueManager,
                                brokerManager,
                                blockFlowSynchronizer))
  //scalastyle:on

  sealed trait Command
  case object Retry extends Command
}

class OutboundBrokerHandler(val selfCliqueInfo: CliqueInfo,
                            val remoteAddress: InetSocketAddress,
                            val blockflow: BlockFlow,
                            val allHandlers: AllHandlers,
                            val cliqueManager: ActorRefT[CliqueManager.Command],
                            val brokerManager: ActorRefT[BrokerManager.Command],
                            val blockFlowSynchronizer: ActorRefT[BlockFlowSynchronizer.Command])(
    implicit val brokerConfig: BrokerConfig,
    val networkSetting: NetworkSetting)
    extends BrokerHandler {

  val until: TimeStamp = TimeStamp.now() + networkSetting.retryTimeout

  IO(Tcp)(context.system) ! Tcp.Connect(remoteBrokerInfo.address)

  var connection: ActorRefT[Tcp.Command]                                  = _
  var brokerConnectionHandler: ActorRefT[BrokerConnectionHandler.Command] = _

  override def receive: Receive = connecting

  def connecting: Receive = {
    case OutboundBrokerHandler.Retry =>
      IO(Tcp)(context.system) ! Tcp.Connect(remoteBrokerInfo.address)

    case _: Tcp.Connected =>
      connection              = ActorRefT[Tcp.Command](sender())
      brokerConnectionHandler = context.actorOf(BrokerConnectionHandler.clique(connection))
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

  override def selfCliqueId: CliqueId = selfCliqueInfo.id

  override def handleHandshakeInfo(remoteCliqueId: CliqueId, remoteBrokerInfo: BrokerInfo): Unit = {
    super.handleHandshakeInfo(remoteCliqueId, remoteBrokerInfo)
    cliqueManager ! CliqueManager.HandShaked(remoteCliqueId, remoteBrokerInfo)
  }
}
