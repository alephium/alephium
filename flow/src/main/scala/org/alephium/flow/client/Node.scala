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

import java.nio.file.Path

import scala.collection.immutable.ArraySeq
import scala.concurrent.{ExecutionContext, Future}

import akka.actor.{ActorSystem, Props}
import com.typesafe.scalalogging.StrictLogging

import org.alephium.flow.Utils
import org.alephium.flow.core._
import org.alephium.flow.handler.AllHandlers
import org.alephium.flow.io.Storages
import org.alephium.flow.network.{Bootstrapper, CliqueManager, DiscoveryServer, TcpController}
import org.alephium.flow.network.broker.MisbehaviorManager
import org.alephium.flow.network.sync.BlockFlowSynchronizer
import org.alephium.flow.setting._
import org.alephium.io.RocksDBSource.ProdSettings
import org.alephium.protocol.vm.LogConfig
import org.alephium.util.{ActorRefT, Env, EventBus, Service}

trait Node extends Service {
  implicit def config: AlephiumConfig
  def system: ActorSystem
  def blockFlow: BlockFlow
  def misbehaviorManager: ActorRefT[MisbehaviorManager.Command]
  def discoveryServer: ActorRefT[DiscoveryServer.Command]
  def tcpController: ActorRefT[TcpController.Command]
  def bootstrapper: ActorRefT[Bootstrapper.Command]
  def cliqueManager: ActorRefT[CliqueManager.Command]
  def eventBus: ActorRefT[EventBus.Message]
  def allHandlers: AllHandlers

  override def subServices: ArraySeq[Service] = ArraySeq.empty

  override protected def startSelfOnce(): Future[Unit] = Future.successful(())

  override protected def stopSelfOnce(): Future[Unit] = Future.successful(())
}

// scalastyle:off method.length
object Node {
  def build(storages: Storages, flowSystem: ActorSystem)(implicit
      executionContext: ExecutionContext,
      config: AlephiumConfig
  ): Node = new Default(storages, flowSystem)

  class Default(storages: Storages, flowSystem: ActorSystem)(implicit
      val executionContext: ExecutionContext,
      val config: AlephiumConfig
  ) extends Node
      with StrictLogging {
    override def serviceName: String = "Node"

    implicit override def system: ActorSystem               = flowSystem
    implicit private val brokerConfig: BrokerSetting        = config.broker
    implicit private val consensusConfig: ConsensusSettings = config.consensus
    implicit private val networkSetting: NetworkSetting     = config.network
    implicit private val discoveryConfig: DiscoverySetting  = config.discovery
    implicit private val miningSetting: MiningSetting       = config.mining
    implicit private val memPoolSetting: MemPoolSetting     = config.mempool
    implicit private val logConfig: LogConfig               = config.node.eventLogConfig

    val blockFlow: BlockFlow = buildBlockFlowUnsafe(storages)

    val misbehaviorManager: ActorRefT[MisbehaviorManager.Command] =
      MisbehaviorManager.build(
        system,
        networkSetting.banDuration,
        networkSetting.penaltyForgiveness,
        networkSetting.penaltyFrequency
      )

    val discoveryProps: Props =
      DiscoveryServer.props(
        networkSetting.bindAddress,
        misbehaviorManager,
        storages.brokerStorage,
        config.discovery.bootstrap
      )
    val discoveryServer: ActorRefT[DiscoveryServer.Command] =
      ActorRefT.build[DiscoveryServer.Command](system, discoveryProps)

    val tcpController: ActorRefT[TcpController.Command] =
      TcpController.build(system, misbehaviorManager)

    val eventBus: ActorRefT[EventBus.Message] =
      ActorRefT.build[EventBus.Message](system, EventBus.props())

    val allHandlers: AllHandlers =
      AllHandlers.build(system, blockFlow, eventBus, storages)

    val blockFlowSynchronizer: ActorRefT[BlockFlowSynchronizer.Command] =
      BlockFlowSynchronizer.build(system, blockFlow, allHandlers)
    lazy val cliqueManager: ActorRefT[CliqueManager.Command] =
      ActorRefT.build(
        system,
        CliqueManager.props(
          blockFlow,
          allHandlers,
          discoveryServer,
          blockFlowSynchronizer,
          discoveryConfig.bootstrap.length
        ),
        "CliqueManager"
      )

    val bootstrapper: ActorRefT[Bootstrapper.Command] =
      ActorRefT.build(
        system,
        Bootstrapper.props(tcpController, cliqueManager, storages.nodeStateStorage),
        "Bootstrapper"
      )
  }

  def buildBlockFlowUnsafe(rootPath: Path): (BlockFlow, Storages) = {
    val typesafeConfig =
      Configs.parseConfigAndValidate(Env.Prod, rootPath, overwrite = true)
    val config = AlephiumConfig.load(typesafeConfig, "alephium")
    val dbPath = rootPath.resolve(config.network.networkId.nodeFolder)
    val storages =
      Storages.createUnsafe(dbPath, "db", ProdSettings.writeOptions)(config.broker, config.node)
    buildBlockFlowUnsafe(storages)(config) -> storages
  }

  def buildBlockFlowUnsafe(storages: Storages)(implicit config: AlephiumConfig): BlockFlow = {
    val nodeStateStorage = storages.nodeStateStorage
    val isInitialized    = Utils.unsafe(nodeStateStorage.isInitialized())
    if (isInitialized) {
      val blockFlow = BlockFlow.fromStorageUnsafe(config, storages)
      checkGenesisBlocks(blockFlow)
      blockFlow
    } else {
      val blockflow = BlockFlow.fromGenesisUnsafe(config, storages)
      Utils.unsafe(nodeStateStorage.setInitialized())
      blockflow
    }
  }

  def checkGenesisBlocks(blockFlow: BlockFlow)(implicit config: AlephiumConfig): Unit = {
    config.broker.chainIndexes.foreach { chainIndex =>
      val configGenesisBlock = config.genesisBlocks(chainIndex.from.value)(chainIndex.to.value)
      val hashes             = Utils.unsafe(blockFlow.getHashes(chainIndex, 0))
      if (hashes.length != 1 || hashes.head != configGenesisBlock.hash) {
        throw new Exception(invalidGenesisBlockMsg)
      }
    }
  }

  val invalidGenesisBlockMsg =
    "Invalid genesis blocks, please wipe out the db history of your current network and resync"
}
