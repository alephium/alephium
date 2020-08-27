package org.alephium.flow.network.sync

import java.net.InetSocketAddress

import akka.actor.{Cancellable, Props, Terminated}

import org.alephium.flow.core.BlockFlow
import org.alephium.flow.handler.{AllHandlers, FlowHandler}
import org.alephium.flow.network.broker.{BrokerHandler, BrokerStatusTracker}
import org.alephium.protocol.Hash
import org.alephium.protocol.model.BrokerInfo
import org.alephium.util.{ActorRefT, AVector, BaseActor, Duration}

object BlockFlowSynchronizer {
  def props(blockflow: BlockFlow, allHandlers: AllHandlers): Props =
    Props(new BlockFlowSynchronizer(blockflow, allHandlers))

  sealed trait Command
  final case class HandShaked(brokerInfo: BrokerInfo)              extends Command
  final case class SyncInventories(hashes: AVector[AVector[Hash]]) extends Command
  final case class Downloaded(hashes: AVector[Hash])               extends Command
  case object Sync                                                 extends Command
}

class BlockFlowSynchronizer(val blockflow: BlockFlow, val allHandlers: AllHandlers)
    extends BaseActor
    with DownloadTracker
    with BrokerStatusTracker {
  import BlockFlowSynchronizer._

  var syncTick: Option[Cancellable] = None

  override def preStart(): Unit = {
    super.preStart()
    syncTick = Some(scheduleCancellable(self, Sync, Duration.ofSecondsUnsafe(2)))
  }

  override def receive: Receive = {
    case HandShaked(remoteBrokerInfo) =>
      log.debug(s"HandShaked with ${remoteBrokerInfo.address}")
      context.watch(sender())
      brokerInfos += ActorRefT[BrokerHandler.Command](sender()) -> remoteBrokerInfo
    case Sync =>
      log.debug(s"Send sync requests to the network")
      allHandlers.flowHandler ! FlowHandler.GetSyncLocators
    case FlowHandler.SyncLocators(locators) =>
      samplePeers.foreach(_ ! BrokerHandler.SyncLocators(locators))
    case SyncInventories(hashes) =>
      log.debug(s"Received sync response from $remoteAddress")
      download(hashes)
    case Downloaded(hashes) =>
      downloaded(hashes)
    case Terminated(broker) =>
      log.debug(s"Connection to ${remoteAddress(ActorRefT(broker))} is closing")
      brokerInfos -= ActorRefT(broker)
  }

  // Only use it when receive messages from inter clique BrokerHandler
  private def remoteAddress: InetSocketAddress = {
    brokerInfos(ActorRefT(sender())).address
  }

  private def remoteAddress(broker: ActorRefT[BrokerHandler.Command]): InetSocketAddress = {
    brokerInfos(broker).address
  }
}
