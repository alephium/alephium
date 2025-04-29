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
import java.nio.file.Paths

import scala.collection.immutable.ArraySeq
import scala.jdk.CollectionConverters._

import com.typesafe.config.{ConfigException, ConfigFactory}
import com.typesafe.config.ConfigValueFactory
import net.ceedubs.ficus.Ficus._
import net.ceedubs.ficus.readers.ArbitraryTypeReader._
import net.ceedubs.ficus.readers.ValueReader

import org.alephium.conf._
import org.alephium.protocol.ALPH
import org.alephium.protocol.config.GroupConfig
import org.alephium.protocol.mining.HashRate
import org.alephium.protocol.model.{
  Address,
  ContractId,
  Difficulty,
  GroupIndex,
  HardFork,
  NetworkId
}
import org.alephium.protocol.vm.{LogConfig, NodeIndexesConfig}
import org.alephium.util.{AlephiumSpec, AVector, Duration, Env, Files, Hex, TimeStamp}

class AlephiumConfigSpec extends AlephiumSpec {
  import ConfigUtils._
  it should "load alephium config" in new AlephiumConfigFixture {
    override val configValues: Map[String, Any] = Map(
      ("alephium.broker.groups", "12"),
      ("alephium.consensus.mainnet.block-target-time", "11 seconds"),
      ("alephium.consensus.rhone.block-target-time", "4 seconds")
    )

    config.broker.groups is 12
    config.broker.brokerNum is 3
    config.broker.groupNumPerBroker is 4
    config.network.networkId is NetworkId(2)
    config.consensus.mainnet.blockTargetTime is Duration.ofSecondsUnsafe(11)
    config.consensus.rhone.blockTargetTime is Duration.ofSecondsUnsafe(4)
    config.network.connectionBufferCapacityInByte is 100000000L
    config.network.syncPeerSampleSizeV1 is 3
    config.network.syncPeerSampleSizeV2 is 5
    config.network.enableP2pV2 is true
  }

  it should "load mainnet config" in {
    val rootPath = Files.tmpDir
    val config   = AlephiumConfig.load(Env.Prod, rootPath, "alephium")

    config.network.networkId is NetworkId.AlephiumMainNet
    config.broker.groups is 4
    config.consensus.mainnet.numZerosAtLeastInHash is 37
    val initialHashRate =
      HashRate.from(
        config.consensus.mainnet.maxMiningTarget,
        config.consensus.mainnet.blockTargetTime
      )(
        config.broker
      )
    initialHashRate is HashRate.unsafe(new BigInteger("549756862464"))
    config.discovery.bootstrap.head is new InetSocketAddress("bootstrap0.alephium.org", 9973)
    config.genesis.allocations.length is 858
    config.genesis.allocations.sumBy(_.amount.value.v) is ALPH.alph(140000000).v
    config.network.lemanHardForkTimestamp is TimeStamp.unsafe(1680170400000L)
    config.genesisBlocks.flatMap(_.map(_.shortHex)).mkString("-") is
      "634cb950-2c637231-2a7b9072-077cd3d3-c9844184-ecb22a45-d63f3b36-d392ac97-2c9d4d28-08906609-ced88aaa-b7f0541b-5f78e23c-c7a2b25d-6b8cdade-6fedfc7f"
    config.network.getHardFork(TimeStamp.now()) is HardFork.Rhone
    config.network.enableP2pV2 is true

    config.node.assetTrieCacheMaxByteSize is 200_000_000
    config.node.contractTrieCacheMaxByteSize is 20_000_000
  }

  it should "load rhone config" in {
    val rootPath = Files.tmpDir
    val config   = AlephiumConfig.load(Env.Prod, rootPath, "alephium")

    config.broker.groups is 4
    config.consensus.rhone.numZerosAtLeastInHash is 37
    config.consensus.rhone.blockTargetTime is Duration.ofSecondsUnsafe(16)
    config.consensus.rhone.uncleDependencyGapTime is Duration.ofSecondsUnsafe(8)
    val initialHashRate =
      HashRate.from(
        config.consensus.rhone.maxMiningTarget,
        config.consensus.rhone.blockTargetTime
      )(
        config.broker
      )
    initialHashRate is HashRate.unsafe(new BigInteger("2199027449856"))
    config.network.networkId is NetworkId.AlephiumMainNet
    config.network.rhoneHardForkTimestamp is TimeStamp.unsafe(1718186400000L)
  }

  it should "throw error when mainnet config has invalid hardfork timestamp" in new AlephiumConfigFixture {
    override val configValues: Map[String, Any] = Map(
      ("alephium.network.network-id", 0),
      ("alephium.network.leman-hard-fork-timestamp", 0)
    )
    assertThrows[IllegalArgumentException](config.network.networkId is NetworkId.AlephiumMainNet)
  }

  it should "check rhone hardfork timestamp" in new AlephiumConfigFixture {
    override val configValues: Map[String, Any] = Map(
      ("alephium.network.network-id", 0),
      ("alephium.network.rhone-hard-fork-timestamp", 0)
    )
    intercept[RuntimeException](
      AlephiumConfig.load(buildNewConfig(), "alephium")
    ).getMessage is
      "Invalid timestamp for rhone hard fork"
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
    val amount = ALPH.oneAlph
    val addresses = AVector(
      "127TathFRczW5LXeNK2n2A6Qi2EpkamcmvwCrr3y18uHT",
      "1HMSFdhPpvPybfWLZiHeBxVbnfTc2L6gkVPHfuJWoZrMA"
    )
    val genesisSetting = GenesisSetting(addresses.map { address =>
      Allocation(Address.asset(address).get, Allocation.Amount(amount), Duration.ofDaysUnsafe(2))
    })

    val configs =
      s"""
         |{
         |  genesis {
         |    allocations = [
         |      {
         |        address = "127TathFRczW5LXeNK2n2A6Qi2EpkamcmvwCrr3y18uHT",
         |        amount = "1000000000000000000",
         |        lock-duration = ${2 * 24 * 60 * 60} seconds
         |      },
         |      {
         |        address = "1HMSFdhPpvPybfWLZiHeBxVbnfTc2L6gkVPHfuJWoZrMA",
         |        amount = "1 ALPH",
         |        lock-duration = 2 days
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

  it should "check root path for mainnet" in {
    val rootPath0 = Paths.get("/user/foo/mainnet")
    val rootPath1 = Paths.get("/user/foo/mainnet/test")
    val rootPath2 = Paths.get("/user/foo/mainne/test")

    Configs.checkRootPath(rootPath0, NetworkId.AlephiumMainNet) isE ()
    Configs.checkRootPath(rootPath1, NetworkId.AlephiumMainNet) isE ()
    Configs.checkRootPath(rootPath2, NetworkId.AlephiumMainNet) isE ()
    Configs.checkRootPath(rootPath0, NetworkId.AlephiumTestNet).isLeft is true
    Configs.checkRootPath(rootPath1, NetworkId.AlephiumTestNet).isLeft is true
    Configs.checkRootPath(rootPath2, NetworkId.AlephiumTestNet) isE ()
  }

  it should "load logConfig" in {
    {
      info("With addresses")
      def address(contractId: String) = {
        Address.contract(ContractId.from(Hex.unsafe(contractId)).value)
      }
      val address1 = address("109b05391a240a0d21671720f62fe39138aaca562676053900b348a51e11ba25")
      val address2 = address("1a21d30793fdf47bf07694017d0d721e94b78dffdc9c8e0b627833b66e5c75d8")
      val logConfig = LogConfig(
        enabled = false,
        indexByTxId = false,
        indexByBlockHash = false,
        contractAddresses = Some(AVector(address1, address2))
      )

      val configs =
        s"""
           |{
           |  event-log {
           |    enabled = false
           |    index-by-tx-id = false
           |    index-by-block-hash = false
           |    contract-addresses = [
           |      ${address1.toBase58}
           |      ${address2.toBase58}
           |    ]
           |  }
           |}
           |""".stripMargin

      ConfigFactory
        .parseString(configs)
        .as[LogConfig]("event-log")(ValueReader[LogConfig]) is logConfig
    }

    {
      info("Without addresses")
      val logConfig = LogConfig.allEnabled()
      val configs =
        s"""
           |{
           |  event-log {
           |    enabled = true
           |    index-by-tx-id = true
           |    index-by-block-hash = true
           |  }
           |}
           |""".stripMargin

      ConfigFactory
        .parseString(configs)
        .as[LogConfig]("event-log")(ValueReader[LogConfig]) is logConfig
    }
  }

  it should "load NodeIndexesConfig" in {
    val configs =
      s"""
         |{
         |  indexes {
         |    tx-output-ref-index = true
         |    subcontract-index = true
         |  }
         |}
         |""".stripMargin

    ConfigFactory
      .parseString(configs)
      .as[NodeIndexesConfig]("indexes")(ValueReader[NodeIndexesConfig]) is NodeIndexesConfig(
      txOutputRefIndex = true,
      subcontractIndex = true
    )
  }

  it should "load NodeSetting" in {
    val configs =
      s"""
         |{
         |  node {
         |    db-sync-write = false
         |    asset-trie-cache-max-byte-size = 200000000
         |    contract-trie-cache-max-byte-size = 20000000
         |    event-log {
         |      enabled = true
         |      index-by-tx-id = true
         |      index-by-block-hash = true
         |    }
         |    indexes {
         |      tx-output-ref-index = true
         |      subcontract-index = false
         |    }
         |  }
         |}
         |""".stripMargin

    ConfigFactory
      .parseString(configs)
      .as[NodeSetting]("node")(ValueReader[NodeSetting]) is NodeSetting(
      dbSyncWrite = false,
      assetTrieCacheMaxByteSize = 200000000,
      contractTrieCacheMaxByteSize = 20000000,
      LogConfig.allEnabled(),
      NodeIndexesConfig(txOutputRefIndex = true, subcontractIndex = false)
    )
  }

  it should "adjust diff for height gaps across chains" in new AlephiumConfigFixture {
    val N               = 123456
    val diff            = Difficulty.unsafe(N)
    val consensusConfig = consensusConfigs.mainnet

    intercept[AssertionError](
      consensusConfig.penalizeDiffForHeightGapLeman(diff, 0, HardFork.Mainnet) is diff
    ).getMessage is "assumption failed"

    consensusConfig.penalizeDiffForHeightGapLeman(diff, -1, HardFork.Leman) is diff
    consensusConfig.penalizeDiffForHeightGapLeman(diff, 0, HardFork.Leman) is diff
    consensusConfig.penalizeDiffForHeightGapLeman(diff, 1, HardFork.Leman) is diff
    consensusConfig.penalizeDiffForHeightGapLeman(diff, 17, HardFork.Leman) is diff
    consensusConfig.penalizeDiffForHeightGapLeman(diff, 18, HardFork.Leman) is
      Difficulty.unsafe(N * 105 / 100)
    consensusConfig.penalizeDiffForHeightGapLeman(diff, 19, HardFork.Leman) is
      Difficulty.unsafe(N * 110 / 100)
    (20 until 18 * 3).foreach { gap =>
      consensusConfig.penalizeDiffForHeightGapLeman(diff, gap, HardFork.Leman) is
        Difficulty.unsafe(N * (100 + 5 * (gap - 17)) / 100)
    }

    consensusConfig.penalizeDiffForHeightGapLeman(diff, -1, HardFork.Rhone) is diff
    consensusConfig.penalizeDiffForHeightGapLeman(diff, 0, HardFork.Rhone) is diff
    consensusConfig.penalizeDiffForHeightGapLeman(diff, 1, HardFork.Rhone) is diff
    consensusConfig.penalizeDiffForHeightGapLeman(diff, 8, HardFork.Rhone) is diff
    consensusConfig.penalizeDiffForHeightGapLeman(diff, 9, HardFork.Rhone) is
      Difficulty.unsafe(N * 103 / 100)
    consensusConfig.penalizeDiffForHeightGapLeman(diff, 10, HardFork.Rhone) is
      Difficulty.unsafe(N * 106 / 100)
    (11 until 18 * 3).foreach { gap =>
      consensusConfig.penalizeDiffForHeightGapLeman(diff, gap, HardFork.Rhone) is
        Difficulty.unsafe(N * (100 + 3 * (gap - 8)) / 100)
    }
  }
}
