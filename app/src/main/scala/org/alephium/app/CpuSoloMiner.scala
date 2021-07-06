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

import akka.actor.{ActorRef, ActorSystem}
import com.typesafe.config.Config

import org.alephium.flow.mining.{ExternalMinerMock, Miner}
import org.alephium.flow.setting.{AlephiumConfig, Configs, Platform}

object CpuSoloMiner extends App {
  val rootPath: Path         = Platform.getRootPath()
  val typesafeConfig: Config = Configs.parseConfigAndValidate(rootPath, overwrite = false)
  val config: AlephiumConfig = AlephiumConfig.load(typesafeConfig, "alephium")

  val system: ActorSystem = ActorSystem("cpu-miner", typesafeConfig)

  val miner: ActorRef =
    system.actorOf(ExternalMinerMock.props(config).withDispatcher("akka.actor.mining-dispatcher"))
  miner ! Miner.Start
}
