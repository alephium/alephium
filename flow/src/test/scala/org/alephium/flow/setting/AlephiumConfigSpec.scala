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

import java.math.BigInteger
import java.net.InetSocketAddress

import scala.collection.immutable.ArraySeq
import scala.jdk.CollectionConverters.*

import com.typesafe.config.{ConfigException, ConfigFactory}
import com.typesafe.config.ConfigValueFactory
import net.ceedubs.ficus.Ficus.*
import net.ceedubs.ficus.readers.ArbitraryTypeReader.*
import net.ceedubs.ficus.readers.ValueReader

import org.alephium.conf.*
import org.alephium.protocol.config.GroupConfig
import org.alephium.protocol.mining.HashRate
import org.alephium.protocol.model.{Address, GroupIndex, NetworkId}
import org.alephium.util.{AlephiumSpec, AVector, Duration, Env, U256}

class AlephiumConfigSpec extends AlephiumSpec {
  it should "load alephium config" in new AlephiumConfigFixture {
    override val configValues: Map[String, Any] = Map(
      ("alephium.broker.groups", "13"),
      ("alephium.consensus.block-target-time", "11 seconds")
    )

    config.broker.groups is 13
    config.network.networkId is NetworkId(2)
    config.consensus.blockTargetTime is Duration.ofSecondsUnsafe(11)
    config.network.connectionBufferCapacityInByte is 100000000L
  }

  it should "load mainnet config" in {
    lazy val rootPath = Platform.getRootPath(Env.Test)
    val config        = AlephiumConfig.load(Env.Prod, rootPath, "alephium")

    config.broker.groups is 4
    config.consensus.numZerosAtLeastInHash is 37
    val initialHashRate =
      HashRate.from(config.consensus.maxMiningTarget, config.consensus.blockTargetTime)(
        config.broker
      )
    initialHashRate is HashRate.unsafe(new BigInteger("549756862464"))
    config.discovery.bootstrap.head is new InetSocketAddress("bootstrap0.alephium.org", 9973)
  }

  it should "load bootstrap config" in {

    case class Bootstrap(addresses: ArraySeq[InetSocketAddress])

    val expected =
      ArraySeq(new InetSocketAddress("127.0.0.1", 1234), new InetSocketAddress("127.0.0.1", 4321))

    ConfigFactory
      .parseString("""{ addresses = ["127.0.0.1:1234", "127.0.0.1:4321"] }""")
      .as[ArraySeq[InetSocketAddress]]("addresses")(inetSocketAddressesReader) is expected

    ConfigFactory
      .parseString("""{ addresses = "127.0.0.1:1234,127.0.0.1:4321" }""")
      .as[ArraySeq[InetSocketAddress]]("addresses")(inetSocketAddressesReader) is expected

    ConfigFactory
      .parseString("""{ addresses = "" }""")
      .as[ArraySeq[InetSocketAddress]]("addresses")(
        inetSocketAddressesReader
      ) is ArraySeq.empty
  }

  it should "load genesis config" in {
    val amount = U256.unsafe(1000000L)
    val addresses = AVector(
      "127TathFRczW5LXeNK2n2A6Qi2EpkamcmvwCrr3y18uHT",
      "1HMSFdhPpvPybfWLZiHeBxVbnfTc2L6gkVPHfuJWoZrMA"
    )
    val genesisSetting = GenesisSetting(addresses.map { address =>
      Allocation(Address.asset(address).get, amount, Duration.ofSecondsUnsafe(2))
    })

    val configs =
      s"""
         |{
         |  genesis {
         |    allocations = [
         |      {
         |        address = "127TathFRczW5LXeNK2n2A6Qi2EpkamcmvwCrr3y18uHT",
         |        amount = "1000000",
         |        lock-duration = 2 seconds
         |      },
         |      {
         |        address = "1HMSFdhPpvPybfWLZiHeBxVbnfTc2L6gkVPHfuJWoZrMA",
         |        amount = "1000000",
         |        lock-duration = 2 seconds
         |      }
         |    ]
         |  }
         |}
         |""".stripMargin

    ConfigFactory
      .parseString(configs)
      .as[GenesisSetting]("genesis")(ValueReader[GenesisSetting]) is genesisSetting

    ConfigFactory
      .parseString("""{ genesis { allocations = [] } }""")
      .as[GenesisSetting]("genesis")(ValueReader[GenesisSetting]) is GenesisSetting(AVector.empty)
  }

  it should "fail to load if miner's addresses are wrong" in new AlephiumConfigFixture {
    val minerAddresses = AVector(
      "49bUQbTo6tHa35U3QC1tsAkEDaryyQGJD2S8eomYfcZx",
      "D9PBcRXK5uzrNYokNMB7oh6JpW86sZajJ5gD845cshED"
    )

    override val configValues: Map[String, Any] = Map(
      (
        "alephium.mining.miner-addresses",
        ConfigValueFactory.fromIterable(minerAddresses.toSeq.asJava)
      )
    )

    assertThrows[ConfigException](AlephiumConfig.load(newConfig, "alephium"))
  }

  class MinerFixture(groupIndexes: Seq[Int]) extends AlephiumConfigFixture {
    val groupConfig1 = new GroupConfig {
      override def groups: Int = 3
    }
    val minerAddresses = groupIndexes.map { g =>
      val groupIndex  = GroupIndex.unsafe(g)(groupConfig1)
      val (_, pubKey) = groupIndex.generateKey(groupConfig1)
      Address.p2pkh(pubKey).toBase58
    }

    override val configValues: Map[String, Any] = Map(
      (
        "alephium.mining.miner-addresses",
        ConfigValueFactory.fromIterable(minerAddresses.toSeq.asJava)
      )
    )
  }

  it should "fail to load if miner's addresses are of wrong indexes" in new MinerFixture(
    Seq(0, 1, 1)
  ) {
    assertThrows[ConfigException](AlephiumConfig.load(newConfig, "alephium"))
  }

  it should "load if miner's addresses are of correct indexes" in new MinerFixture(
    Seq(0, 1, 2)
  ) {
    config.mining.minerAddresses.get.toSeq is minerAddresses.map(str => Address.asset(str).get)
  }
}
