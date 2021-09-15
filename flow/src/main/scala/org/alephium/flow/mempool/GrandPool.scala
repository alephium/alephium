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

package org.alephium.flow.mempool

import org.alephium.flow.core.BlockFlow
import org.alephium.flow.setting.MemPoolSetting
import org.alephium.io.IOResult
import org.alephium.protocol.config.BrokerConfig
import org.alephium.protocol.model.{ChainIndex, GroupIndex, TransactionTemplate}
import org.alephium.util.{AVector, TimeStamp}

class GrandPool(val mempools: AVector[MemPool])(implicit
    val brokerConfig: BrokerConfig
) {
  @inline def getMemPool(mainGroup: GroupIndex): MemPool = {
    mempools(brokerConfig.groupIndexOfBroker(mainGroup))
  }

  @inline def getMemPool(chainIndex: ChainIndex): MemPool = {
    getMemPool(chainIndex.from)
  }

  def cleanAndExtractReadyTxs(
      blockFlow: BlockFlow,
      timeStampThreshold: TimeStamp
  ): AVector[MemPool.CleanupResult] = {
    mempools.map(_.cleanAndExtractReadyTxs(blockFlow, timeStampThreshold))
  }

  def cleanPendingPool(
      blockFlow: BlockFlow
  ): AVector[IOResult[AVector[(TransactionTemplate, TimeStamp)]]] = {
    mempools.map(_.cleanPendingPool(blockFlow))
  }
}

object GrandPool {
  def empty(implicit brokerConfig: BrokerConfig, memPoolSetting: MemPoolSetting): GrandPool = {
    val mempools = AVector.tabulate(brokerConfig.groupNumPerBroker) { idx =>
      val group = GroupIndex.unsafe(brokerConfig.groupRange(idx))
      MemPool.empty(group)
    }
    new GrandPool(mempools)
  }
}
