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

import scala.collection.mutable
import scala.util.Random

import org.alephium.flow.network.broker.BrokerHandler
import org.alephium.flow.setting.NetworkSetting
import org.alephium.protocol.model.BrokerInfo
import org.alephium.util.{ActorRefT, AVector}

trait BrokerStatusTracker {
  def networkSetting: NetworkSetting

  val brokerInfos: mutable.ArrayBuffer[(ActorRefT[BrokerHandler.Command], BrokerInfo)] =
    mutable.ArrayBuffer.empty

  def samplePeersSize(): Int = {
    val peerSize = Math.sqrt(brokerInfos.size.toDouble).toInt
    Math.min(peerSize, networkSetting.syncPeerSampleSize)
  }

  def samplePeers(): AVector[(ActorRefT[BrokerHandler.Command], BrokerInfo)] = {
    val peerSize   = samplePeersSize()
    val startIndex = Random.nextInt(brokerInfos.size)
    AVector.tabulate(peerSize) { k =>
      brokerInfos((startIndex + k) % brokerInfos.size)
    }
  }
}
