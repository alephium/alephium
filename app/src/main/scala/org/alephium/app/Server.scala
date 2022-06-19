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

package org.alephium.app

import java.nio.file.Path

import scala.collection.immutable.ArraySeq
import scala.concurrent.{ExecutionContext, Future}

import akka.actor.ActorSystem

import org.alephium.flow.client.Node
import org.alephium.flow.io.Storages
import org.alephium.flow.mining.{CpuMiner, Miner, MinerApiController}
import org.alephium.flow.setting.AlephiumConfig
import org.alephium.io.RocksDBSource.Settings
import org.alephium.util.{ActorRefT, Service}
import org.alephium.wallet.WalletApp
import org.alephium.wallet.config.WalletConfig
import org.alephium.wallet.service.WalletService

trait Server extends Service {
  def flowSystem: ActorSystem
  implicit def executionContext: ExecutionContext

  implicit def config: AlephiumConfig
  implicit def apiConfig: ApiConfig
  def storages: Storages

  lazy val node: Node = Node.build(storages, flowSystem)
  lazy val walletApp: Option[WalletApp] = Option.when(config.network.isCoordinator) {
    val walletConfig: WalletConfig = WalletConfig(
      port = None,
      config.wallet.secretDir,
      config.wallet.lockingTimeout,
      apiConfig.apiKey,
      WalletConfig.BlockFlow(
        apiConfig.networkInterface.getHostAddress,
        config.network.restPort,
        config.broker.groups,
        apiConfig.blockflowFetchMaxAge,
        apiConfig.apiKey
      )
    )

    new WalletApp(walletConfig)(executionContext)
  }

  def blocksExporter: BlocksExporter

  lazy val restServer: RestServer =
    RestServer(node, miner, blocksExporter, walletApp.map(_.walletServer))(
      config.broker,
      apiConfig,
      executionContext
    )
  lazy val webSocketServer: WebSocketServer =
    WebSocketServer(node)(flowSystem, apiConfig, executionContext)
  lazy val walletService: Option[WalletService] = walletApp.map(_.walletService)

  lazy val miner: ActorRefT[Miner.Command] = {
    val props = CpuMiner.props(node)
    ActorRefT.build(flowSystem, props, s"Miner")
  }

  override lazy val subServices: ArraySeq[Service] = {
    ArraySeq(restServer, webSocketServer, node) ++ ArraySeq.from[Service](walletService.toList)
  }

  override protected def startSelfOnce(): Future[Unit] = Future {
    val props =
      MinerApiController
        .props(node.allHandlers)(
          config.broker,
          config.network,
          config.mining
        )
    ActorRefT.build(flowSystem, props, s"MinerApi")
    ()
  }
  override protected def stopSelfOnce(): Future[Unit] = Future.successful(())
}

object Server {
  def apply(rootPath: Path, flowSystem: ActorSystem)(implicit
      config: AlephiumConfig,
      apiConfig: ApiConfig,
      executionContext: ExecutionContext
  ): Server = {
    new Impl(rootPath, flowSystem)
  }

  final private class Impl(
      rootPath: Path,
      val flowSystem: ActorSystem
  )(implicit
      val config: AlephiumConfig,
      val apiConfig: ApiConfig,
      val executionContext: ExecutionContext
  ) extends Server {
    val dbPath                = rootPath.resolve(config.network.networkId.nodeFolder)
    val storageFolder: String = "db"
    val writeOptions = if (config.node.dbSyncWrite) Settings.syncWrite else Settings.writeOptions

    val storages: Storages =
      Storages.createUnsafe(dbPath, storageFolder, writeOptions)(config.broker)

    val blocksExporter: BlocksExporter = new BlocksExporter(node.blockFlow, rootPath)(config.broker)
  }
}
