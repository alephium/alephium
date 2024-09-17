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
import org.alephium.protocol.model.{BrokerInfo, ChainTip}
import org.alephium.util.{ActorRefT, AVector}

object BrokerStatusTracker {
  type BrokerActor = ActorRefT[BrokerHandler.Command]

  final class BrokerStatus(val info: BrokerInfo) {
    private var tips: Option[AVector[ChainTip]] = None

    def updateTips(newTips: AVector[ChainTip]): Unit = tips = Some(newTips)
  }

  object BrokerStatus {
    def apply(info: BrokerInfo): BrokerStatus = new BrokerStatus(info)
  }
}

trait BrokerStatusTracker {
  import BrokerStatusTracker._
  def networkSetting: NetworkSetting

  val brokers: mutable.ArrayBuffer[(BrokerActor, BrokerStatus)] =
    mutable.ArrayBuffer.empty

  def samplePeersSize(): Int = {
    val peerSize = Math.sqrt(brokers.size.toDouble).toInt
    Math.min(peerSize, networkSetting.syncPeerSampleSize)
  }

  def samplePeers(): AVector[(BrokerActor, BrokerStatus)] = {
    val peerSize   = samplePeersSize()
    val startIndex = Random.nextInt(brokers.size)
    AVector.tabulate(peerSize) { k =>
      brokers((startIndex + k) % brokers.size)
    }
  }
}
