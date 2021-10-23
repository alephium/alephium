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

package org.alephium.flow.setting

import scala.jdk.CollectionConverters._

import com.typesafe.config.{ConfigFactory, ConfigValueFactory}

import org.alephium.protocol.{ALF, PrivateKey, PublicKey}
import org.alephium.protocol.config.GroupConfig
import org.alephium.protocol.model.{Address, GroupIndex}
import org.alephium.util.{AVector, Duration, Env, Number, U256}

trait AlephiumConfigFixture extends RandomPortsConfigFixture {

  val configValues: Map[String, Any] = Map.empty

  val genesisBalance: U256 = ALF.alf(Number.million)

  lazy val env      = Env.resolve()
  lazy val rootPath = Platform.getRootPath(env)

  lazy val newConfig = ConfigFactory
    .parseMap(
      (configPortsValues ++ configValues).view.mapValues(ConfigValueFactory.fromAnyRef).toMap.asJava
    )
    .withFallback(Configs.parseConfig(Env.currentEnv, rootPath, overwrite = true))

  lazy val groups0 = newConfig.getInt("alephium.broker.groups")

  lazy val groupConfig: GroupConfig = new GroupConfig { override def groups: Int = groups0 }

  lazy val genesisKeys =
    AVector.tabulate[(PrivateKey, PublicKey, U256)](groups0) { i =>
      val groupIndex              = GroupIndex.unsafe(i)(groupConfig)
      val (privateKey, publicKey) = groupIndex.generateKey(groupConfig)
      (privateKey, publicKey, genesisBalance)
    }

  implicit lazy val config = {
    val tmp = AlephiumConfig
      .load(newConfig)

    val allocations = genesisKeys.map { case (_, pubKey, amount) =>
      Allocation(Address.p2pkh(pubKey), amount, Duration.zero)
    }
    tmp.copy(genesis = GenesisSetting(allocations))
  }
  implicit lazy val brokerConfig     = config.broker
  implicit lazy val consensusConfig  = config.consensus
  implicit lazy val networkConfig    = config.network
  implicit lazy val discoverySetting = config.discovery
  implicit lazy val memPoolSetting   = config.mempool
  implicit lazy val miningSetting    = config.mining
}
