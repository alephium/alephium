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

import org.alephium.flow.client.{Miner, Node}
import org.alephium.flow.io.Storages
import org.alephium.flow.setting.AlephiumConfig
import org.alephium.io.RocksDBSource.Settings
import org.alephium.util.{ActorRefT, Service}
import org.alephium.wallet.WalletApp
import org.alephium.wallet.config.WalletConfig
import org.alephium.wallet.service.WalletService

trait Server extends Service {
  implicit def system: ActorSystem
  implicit def executionContext: ExecutionContext

  implicit def config: AlephiumConfig
  implicit def apiConfig: ApiConfig
  def storages: Storages

  override lazy val subServices: ArraySeq[Service] = {
    ArraySeq(restServer, webSocketServer, node) ++ ArraySeq.from[Service](walletService.toList)
  }
  override protected def startSelfOnce(): Future[Unit] =
    Future.successful(())

  override protected def stopSelfOnce(): Future[Unit] = {
    Future.successful(())
  }
}

class ServerImpl(rootPath: Path)(implicit val config: AlephiumConfig,
                                 val apiConfig: ApiConfig,
                                 val system: ActorSystem,
                                 val executionContext: ExecutionContext)
    extends Server {
  private val storages: Storages = {
    val postfix  = s"${config.broker.brokerId}-${config.network.bindAddress.getPort}"
    val dbFolder = "db-" + postfix

    Storages.createUnsafe(rootPath, dbFolder, Settings.writeOptions)(config.broker)
  }

  val node: Node = Node.build(storages)

  lazy val miner: ActorRefT[Miner.Command] = {
    val props =
      Miner
        .props(node)
        .withDispatcher("akka.actor.mining-dispatcher")
    ActorRefT.build(system, props, s"FairMiner")
  }

  private lazy val walletApp: Option[WalletApp] = Option.when(config.network.isCoordinator) {
    val walletConfig: WalletConfig = WalletConfig(
      config.wallet.port,
      config.wallet.secretDir,
      config.network.networkType,
      WalletConfig.BlockFlow(
        apiConfig.networkInterface.getHostAddress,
        config.network.restPort,
        config.broker.groups
      )
    )

    new WalletApp(walletConfig)
  }
  lazy val restServer: RestServer               = RestServer(node, miner, walletApp.map(_.walletServer))
  lazy val webSocketServer: WebSocketServer     = WebSocketServer(node)
  lazy val walletService: Option[WalletService] = walletApp.map(_.walletService)

  lazy val miner: ActorRefT[Miner.Command] = {
    val props =
      Miner
        .props(node)(config.broker, config.consensus, config.mining)
        .withDispatcher("akka.actor.mining-dispatcher")
    ActorRefT.build(system, props, s"Miner")
  }

  override lazy val subServices: ArraySeq[Service] = {
    ArraySeq(restServer, webSocketServer, node) ++ ArraySeq.from[Service](walletService.toList)
  }

  override protected def startSelfOnce(): Future[Unit] = Future.successful(())
  override protected def stopSelfOnce(): Future[Unit]  = Future.successful(())
}

object Server {
  def apply(rootPath: Path)(implicit config: AlephiumConfig,
                            apiConfig: ApiConfig,
                            system: ActorSystem,
                            executionContext: ExecutionContext): Server = {
    new Impl(rootPath)
  }

  def storageFolder(implicit config: AlephiumConfig): String = {
    s"db-${config.broker.brokerId}-${config.network.bindAddress.getPort}"
  }

  private final class Impl(rootPath: Path)(implicit val config: AlephiumConfig,
                                           val apiConfig: ApiConfig,
                                           val system: ActorSystem,
                                           val executionContext: ExecutionContext)
      extends Server {
    val storages: Storages = {
      Storages.createUnsafe(rootPath, storageFolder, Settings.writeOptions)(config.broker)
    }
  }
}
