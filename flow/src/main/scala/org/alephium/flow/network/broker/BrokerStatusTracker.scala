package org.alephium.flow.network.broker

import java.net.InetSocketAddress

import scala.collection.mutable

import org.alephium.protocol.model.BrokerInfo
import org.alephium.util.{ActorRefT, AVector}

object BrokerStatusTracker {
  final case class Status(lastSeenHeights: AVector[Int])

  final case class ConnectingBroker(remoteAddress: InetSocketAddress,
                                    localAddress: InetSocketAddress,
                                    handler: ActorRefT[BrokerHandler.Command])
  final case class HandShakedBroker(brokerInfo: BrokerInfo)

  type ConnectingBrokers = mutable.HashMap[ActorRefT[BrokerHandler.Command], ConnectingBroker]
  type HandShakedBrokers = mutable.HashSet[HandShakedBroker]
}

trait BrokerStatusTracker {
  import BrokerStatusTracker._

  val brokerInfos: mutable.HashMap[ActorRefT[BrokerHandler.Command], BrokerInfo] =
    mutable.HashMap.empty
  val brokerStatus: mutable.HashMap[ActorRefT[BrokerHandler.Command], Status] =
    mutable.HashMap.empty

  def samplePeers: AVector[ActorRefT[BrokerHandler.Command]] = AVector.from(brokerInfos.keys)
}
