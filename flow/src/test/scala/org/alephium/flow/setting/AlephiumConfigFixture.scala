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

import org.alephium.flow.setting._
import org.alephium.protocol.{ALPH, PrivateKey, PublicKey}
import org.alephium.protocol.config.{GroupConfig, NetworkConfig, NetworkConfigFixture}
import org.alephium.protocol.model.{Address, GroupIndex, HardFork}
import org.alephium.protocol.vm.{LogConfig, NodeIndexesConfig}
import org.alephium.util.{AVector, Duration, Env, Number, U256}

trait AlephiumConfigFixture extends RandomPortsConfigFixture {

  val configValues: Map[String, Any] = Map.empty

  val genesisBalance: U256 = ALPH.alph(Number.million)

  private var networkConfigValues: Option[Map[String, Any]] = None

  private def setNetworkConfigValues(config: NetworkConfig): Unit = {
    networkConfigValues = Some(
      Map(
        ("alephium.network.leman-hard-fork-timestamp", config.lemanHardForkTimestamp.millis),
        ("alephium.network.rhone-hard-fork-timestamp", config.rhoneHardForkTimestamp.millis),
        ("alephium.network.danube-hard-fork-timestamp", config.danubeHardForkTimestamp.millis)
      )
    )
  }

  def setHardFork(hardFork: HardFork): Unit = {
    setNetworkConfigValues(NetworkConfigFixture.getNetworkConfig(hardFork))
  }

  def setHardForkSince(hardFork: HardFork): Unit = {
    setNetworkConfigValues(NetworkConfigFixture.getNetworkConfigSince(hardFork))
  }

  def setHardForkBefore(hardFork: HardFork): Unit = {
    setNetworkConfigValues(NetworkConfigFixture.getNetworkConfigBefore(hardFork))
  }

  lazy val env      = Env.resolve()
  lazy val rootPath = Platform.getRootPath(env)

  def buildNewConfig() = {
    val predefined = ConfigFactory
      .parseMap(
        (configPortsValues ++ networkConfigValues.getOrElse(Map.empty) ++ configValues).view
          .mapValues {
            case value: AVector[_] =>
              ConfigValueFactory.fromIterable(value.toIterable.asJava)
            case value =>
              ConfigValueFactory.fromAnyRef(value)
          }
          .toMap
          .asJava
      )
    Configs.parseConfig(Env.currentEnv, rootPath, overwrite = true, predefined)
  }
  lazy val newConfig = buildNewConfig()

  lazy val groups0 = newConfig.getInt("alephium.broker.groups")

  lazy val groupConfig: GroupConfig = new GroupConfig { override def groups: Int = groups0 }

  lazy val genesisKeys =
    AVector.tabulate[(PrivateKey, PublicKey, U256)](groups0) { i =>
      val groupIndex              = GroupIndex.unsafe(i)(groupConfig)
      val (privateKey, publicKey) = groupIndex.generateKey(groupConfig)
      (privateKey, publicKey, genesisBalance)
    }

  implicit lazy val config: AlephiumConfig = {
    val tmp = AlephiumConfig
      .load(newConfig)

    val allocations = genesisKeys.map { case (_, pubKey, amount) =>
      Allocation(Address.p2pkh(pubKey), Allocation.Amount(amount), Duration.zero)
    }
    tmp.copy(genesis = GenesisSetting(allocations))
  }

  implicit lazy val brokerConfig: BrokerSetting          = config.broker
  implicit lazy val consensusConfigs: ConsensusSettings  = config.consensus
  implicit lazy val networkConfig: NetworkSetting        = config.network
  implicit lazy val discoverySetting: DiscoverySetting   = config.discovery
  implicit lazy val memPoolSetting: MemPoolSetting       = config.mempool
  implicit lazy val miningSetting: MiningSetting         = config.mining
  implicit lazy val nodeSetting: NodeSetting             = config.node
  implicit lazy val logConfig: LogConfig                 = config.node.eventLogConfig
  implicit lazy val nodeIndexesConfig: NodeIndexesConfig = config.node.indexesConfig
}
