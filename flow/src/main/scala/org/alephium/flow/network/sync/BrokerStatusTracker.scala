// Copyright 2018 The Alephium Authors
// This file is part of the alephium project.
//
// The library is free software: you can redistribute it and/or modify
// it under the terms of the GNU Lesser General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// The library is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
// GNU Lesser General Public License for more details.
//
// You should have received a copy of the GNU Lesser General Public License
// along with the library. If not, see <http://www.gnu.org/licenses/>.

package org.alephium.flow.network.sync

import java.net.InetSocketAddress

import scala.collection.mutable
import scala.util.Random

import org.alephium.flow.network.broker.BrokerHandler
import org.alephium.protocol.model.BrokerInfo
import org.alephium.util.{ActorRefT, AVector}

object BrokerStatusTracker {
  final case class Status(lastSeenHeights: AVector[Int])

  final case class ConnectingBroker(
      remoteAddress: InetSocketAddress,
      localAddress: InetSocketAddress,
      handler: ActorRefT[BrokerHandler.Command]
  )
  final case class HandShakedBroker(brokerInfo: BrokerInfo)

  type ConnectingBrokers = mutable.HashMap[ActorRefT[BrokerHandler.Command], ConnectingBroker]
  type HandShakedBrokers = mutable.HashSet[HandShakedBroker]
}

trait BrokerStatusTracker {
  val brokerInfos: mutable.ArrayBuffer[(ActorRefT[BrokerHandler.Command], BrokerInfo)] =
    mutable.ArrayBuffer.empty

  def samplePeersSize(): Int = {
    val peerSize = Math.sqrt(brokerInfos.size.toDouble).toInt
    Math.min(peerSize, 3)
  }

  def samplePeers(): AVector[(ActorRefT[BrokerHandler.Command], BrokerInfo)] = {
    val peerSize   = samplePeersSize()
    val startIndex = Random.nextInt(brokerInfos.size)
    AVector.tabulate(peerSize) { k =>
      brokerInfos((startIndex + k) % brokerInfos.size)
    }
  }
}
