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
import org.alephium.flow.setting.AlephiumConfig
import org.alephium.util.{ActorRefT, EventBus, Service}

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
    implicit override def system: ActorSystem = flowSystem
    implicit private val brokerConfig         = config.broker
    implicit private val consensusConfig      = config.consensus
    implicit private val networkSetting       = config.network
    implicit private val discoveryConfig      = config.discovery
    implicit private val miningSetting        = config.mining
    implicit private val memPoolSetting       = config.mempool
    implicit private val logConfig            = config.node.logConfig

    val blockFlow: BlockFlow = buildBlockFlowUnsafe(storages)

    val misbehaviorManager: ActorRefT[MisbehaviorManager.Command] =
      ActorRefT.build(
        system,
        MisbehaviorManager.props(
          networkSetting.banDuration,
          networkSetting.penaltyForgiveness,
          networkSetting.penaltyFrequency
        )
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
      ActorRefT
        .build[TcpController.Command](
          system,
          TcpController.props(config.network.bindAddress, misbehaviorManager)
        )

    val eventBus: ActorRefT[EventBus.Message] =
      ActorRefT.build[EventBus.Message](system, EventBus.props())

    val allHandlers: AllHandlers =
      AllHandlers.build(system, blockFlow, eventBus, storages)

    val blockFlowSynchronizer: ActorRefT[BlockFlowSynchronizer.Command] =
      ActorRefT.build(system, BlockFlowSynchronizer.props(blockFlow, allHandlers))
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

  def buildBlockFlowUnsafe(storages: Storages)(implicit config: AlephiumConfig): BlockFlow = {
    val nodeStateStorage = storages.nodeStateStorage
    val isInitialized    = Utils.unsafe(nodeStateStorage.isInitialized())
    if (isInitialized) {
      BlockFlow.fromStorageUnsafe(config, storages)
    } else {
      val blockflow = BlockFlow.fromGenesisUnsafe(config, storages)
      Utils.unsafe(nodeStateStorage.setInitialized())
      blockflow
    }
  }
}
