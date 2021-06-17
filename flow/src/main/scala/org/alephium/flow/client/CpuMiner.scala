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

package org.alephium.flow.client

import akka.actor.Props

import org.alephium.flow.handler.{AllHandlers, BlockChainHandler, ViewHandler}
import org.alephium.flow.model.{BlockFlowTemplate, MiningBlob}
import org.alephium.flow.model.DataOrigin.Local
import org.alephium.flow.setting.{AlephiumConfig, MiningSetting}
import org.alephium.protocol.config.BrokerConfig
import org.alephium.protocol.model.{Block, ChainIndex, NetworkType}
import org.alephium.util.ActorRefT

object CpuMiner {
  def props(node: Node)(implicit config: AlephiumConfig): Props = {
    props(config.network.networkType, node.allHandlers)(config.broker, config.mining)
  }

  def props(networkType: NetworkType, allHandlers: AllHandlers)(implicit
      brokerConfig: BrokerConfig,
      miningConfig: MiningSetting
  ): Props = {
    Props(new CpuMiner(networkType, allHandlers))
  }
}

class CpuMiner(val networkType: NetworkType, val allHandlers: AllHandlers)(implicit
    val brokerConfig: BrokerConfig,
    val miningConfig: MiningSetting
) extends Miner {

  def receive: Receive = handleMining orElse handleMiningTasks

  def subscribeForTasks(): Unit = {
    allHandlers.viewHandler ! ViewHandler.Subscribe
  }

  def unsubscribeTasks(): Unit = {
    allHandlers.viewHandler ! ViewHandler.Unsubscribe
  }

  def publishNewBlock(block: Block): Unit = {
    val handlerMessage = BlockChainHandler.Validate(block, ActorRefT(self), Local)
    allHandlers.getBlockHandlerUnsafe(block.chainIndex) ! handlerMessage
  }

  def handleMiningTasks: Receive = {
    case ViewHandler.NewTemplates(templates) =>
      if (miningStarted) {
        updateAndStartTasks(templates)
      }
    case BlockChainHandler.BlockAdded(hash) =>
      setIdle(ChainIndex.from(hash))
    case BlockChainHandler.InvalidBlock(hash) =>
      log.error(s"Mined an invalid block ${hash.shortHex}")
      setIdle(ChainIndex.from(hash))
  }

  def updateAndStartTasks(templates: IndexedSeq[IndexedSeq[BlockFlowTemplate]]): Unit = {
    for {
      fromShift <- 0 until brokerConfig.groupNumPerBroker
      to        <- 0 until brokerConfig.groups
    } {
      val miningBlob = MiningBlob.from(templates(fromShift)(to))
      pendingTasks(fromShift)(to) = miningBlob
    }
    startNewTasks()
  }
}
