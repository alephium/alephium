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

import java.net.InetSocketAddress
import java.nio.file.Path

import akka.actor.{ActorRef, ActorSystem}
import com.typesafe.config.Config
import com.typesafe.scalalogging.StrictLogging

import org.alephium.flow.mining.{ExternalMinerMock, Miner}
import org.alephium.flow.setting.{AlephiumConfig, Configs, Platform}
import org.alephium.util.{AVector, Env}

object CpuSoloMiner extends App {
  val rootPath: Path = Platform.getRootPath()
  val typesafeConfig: Config =
    Configs.parseConfigAndValidate(Env.currentEnv, rootPath, overwrite = false)
  val config: AlephiumConfig = AlephiumConfig.load(typesafeConfig, "alephium")
  val system: ActorSystem    = ActorSystem("cpu-miner", typesafeConfig)

  new CpuSoloMiner(config, system, args.headOption)
}

class CpuSoloMiner(config: AlephiumConfig, system: ActorSystem, rawApiAddresses: Option[String])
    extends StrictLogging {
  val miner: ActorRef = {
    val props = rawApiAddresses match {
      case None =>
        ExternalMinerMock.singleNode(config).withDispatcher("akka.actor.mining-dispatcher")
      case Some(rawAddresses) =>
        val addresses = rawAddresses.split(",").map(parseHostAndPort)
        ExternalMinerMock
          .props(config, AVector.unsafe(addresses))
          .withDispatcher("akka.actor.mining-dispatcher")
    }
    system.actorOf(props)
  }

  miner ! Miner.Start

  private def parseHostAndPort(unparsedHostAndPort: String): InetSocketAddress = {
    val hostAndPort = """([a-zA-Z0-9\.\-]+)\s*:\s*(\d+)""".r
    unparsedHostAndPort match {
      case hostAndPort(host, port) =>
        new InetSocketAddress(host, port.toInt)
      case _ =>
        logger.error(s"Invalid miner API address: $unparsedHostAndPort")
        sys.exit(1)
    }
  }
}
