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
import java.net.{InetAddress, InetSocketAddress}
import java.nio.file.Path

import scala.collection.immutable.ArraySeq

import akka.actor.ActorRef
import akka.io.Tcp
import akka.util.ByteString
import com.typesafe.config.{Config, ConfigFactory}
import net.ceedubs.ficus.Ficus._
import net.ceedubs.ficus.readers.ArbitraryTypeReader._
import net.ceedubs.ficus.readers.ValueReader

import org.alephium.conf._
import org.alephium.flow.core.maxForkDepth
import org.alephium.flow.network.nat.Upnp
import org.alephium.protocol.{ALPH, Hash}
import org.alephium.protocol.config._
import org.alephium.protocol.mining.Emission
import org.alephium.protocol.model.{Address, Block, Difficulty, HardFork, NetworkId, Target, Weight}
import org.alephium.protocol.vm.LogConfig
import org.alephium.util._

final case class BrokerSetting(groups: Int, brokerNum: Int, brokerId: Int) extends BrokerConfig {
  override lazy val groupNumPerBroker: Int = groups / brokerNum
}

//scalastyle:off magic.number
@SuppressWarnings(Array("org.wartremover.warts.OptionPartial"))
final case class ConsensusSetting(
    blockTargetTime: Duration,
    uncleDependencyGapTime: Duration,
    numZerosAtLeastInHash: Int,
    emission: Emission
) extends ConsensusConfig {
  val maxMiningTarget: Target =
    Target.unsafe(BigInteger.ONE.shiftLeft(256 - numZerosAtLeastInHash).subtract(BigInteger.ONE))
  val minMiningDiff: Difficulty = maxMiningTarget.getDifficulty()
  val minBlockWeight: Weight    = Weight.from(maxMiningTarget)

  val expectedTimeSpan: Duration       = blockTargetTime
  val powAveragingWindow: Int          = 17
  val expectedWindowTimeSpan: Duration = expectedTimeSpan.timesUnsafe(powAveragingWindow.toLong)

  private val crossShardHeightGapThresholdPreRhone: Int = powAveragingWindow
  private val crossShardHeightGapThreshold: Int         = powAveragingWindow / 2

  def penalizeDiffForHeightGapLeman(diff: Difficulty, gap: Int, hardFork: HardFork): Difficulty = {
    assume(hardFork.isLemanEnabled())
    val (threshold, factor) = if (hardFork.isRhoneEnabled()) {
      crossShardHeightGapThreshold -> 3
    } else {
      crossShardHeightGapThresholdPreRhone -> 5
    }
    val delta = gap - threshold
    if (delta > 0) {
      diff.times(100 + factor * delta).divide(100)
    } else {
      diff
    }
  }

  val diffAdjustDownMax: Int = 16
  val diffAdjustUpMax: Int   = 8
  val windowTimeSpanMin: Duration =
    (expectedWindowTimeSpan * (100L - diffAdjustDownMax)).get divUnsafe 100L
  val windowTimeSpanMax: Duration =
    (expectedWindowTimeSpan * (100L + diffAdjustUpMax)).get divUnsafe 100L

  private[setting] val tipsPruneDuration: Duration =
    blockTargetTime.timesUnsafe(maxForkDepth.toLong)
}
//scalastyle:on

final case class ConsensusSettings(
    mainnet: ConsensusSetting,
    rhone: ConsensusSetting,
    blockCacheCapacityPerChain: Int
) extends ConsensusConfigs {
  override def getConsensusConfig(hardFork: HardFork): ConsensusSetting = {
    if (hardFork.isRhoneEnabled()) rhone else mainnet
  }
  override def getConsensusConfig(
      ts: TimeStamp
  )(implicit networkConfig: NetworkConfig): ConsensusSetting = {
    getConsensusConfig(networkConfig.getHardFork(ts))
  }

  val conflictCacheKeepDuration: Duration =
    Math.max(
      mainnet.expectedTimeSpan,
      rhone.expectedTimeSpan
    ) timesUnsafe blockCacheCapacityPerChain.toLong
  val tipsPruneDuration: Duration = Math.max(mainnet.tipsPruneDuration, rhone.tipsPruneDuration)

  val recentBlockHeightDiff: Int         = 30
  val recentBlockTimestampDiff: Duration = Duration.ofMinutesUnsafe(30)
}

final case class MiningSetting(
    minerAddresses: Option[AVector[Address.Asset]],
    nonceStep: U256,
    batchDelay: Duration,
    apiInterface: InetAddress,
    pollingInterval: Duration,
    jobCacheSizePerChain: Int
)

final case class NetworkSetting(
    networkId: NetworkId,
    lemanHardForkTimestamp: TimeStamp,
    rhoneHardForkTimestamp: TimeStamp,
    noPreMineProof: ByteString,
    maxOutboundConnectionsPerGroup: Int,
    maxInboundConnectionsPerGroup: Int,
    maxCliqueFromSameIp: Int,
    pingFrequency: Duration,
    retryTimeout: Duration,
    banDuration: Duration,
    penaltyForgiveness: Duration,
    penaltyFrequency: Duration,
    backoffBaseDelay: Duration,
    backoffMaxDelay: Duration,
    backoffResetDelay: Duration,
    connectionBufferCapacityInByte: Long,
    fastSyncFrequency: Duration,
    stableSyncFrequency: Duration,
    syncPeerSampleSize: Int,
    syncCleanupFrequency: Duration,
    syncExpiryPeriod: Duration,
    dependencyExpiryPeriod: Duration,
    updateSyncedFrequency: Duration,
    txsBroadcastDelay: Duration,
    upnp: UpnpSettings,
    bindAddress: InetSocketAddress,
    internalAddress: InetSocketAddress,
    coordinatorAddress: InetSocketAddress,
    externalAddress: Option[InetSocketAddress],
    restPort: Int,
    wsPort: Int,
    minerApiPort: Int,
    connectionBuild: ActorRef => ActorRefT[Tcp.Command]
) extends NetworkConfig {
  val isCoordinator: Boolean = internalAddress == coordinatorAddress

  def handshakeTimeout: Duration = retryTimeout

  val externalAddressInferred: Option[InetSocketAddress] = externalAddress.orElse {
    if (upnp.enabled) {
      Upnp.getUpnpClient(upnp).map { client =>
        val bindingPort = bindAddress.getPort
        client.addPortMapping(bindingPort, bindingPort)
        new InetSocketAddress(client.externalAddress, bindingPort)
      }
    } else {
      None
    }
  }
}

final case class UpnpSettings(
    enabled: Boolean,
    httpTimeout: Option[Duration],
    discoveryTimeout: Option[Duration]
)

final case class DiscoverySetting(
    bootstrap: ArraySeq[InetSocketAddress],
    scanFrequency: Duration,
    scanFastFrequency: Duration,
    fastScanPeriod: Duration,
    initialDiscoveryPeriod: Duration,
    neighborsPerGroup: Int,
    maxCliqueFromSameIp: Int
) extends DiscoveryConfig

final case class MemPoolSetting(
    mempoolCapacityPerChain: Int,
    txMaxNumberPerBlock: Int,
    cleanMempoolFrequency: Duration,
    unconfirmedTxExpiryDuration: Duration,
    batchBroadcastTxsFrequency: Duration,
    batchDownloadTxsFrequency: Duration,
    cleanMissingInputsTxFrequency: Duration,
    autoMineForDev: Boolean // for dev only
)

final case class WalletSetting(enabled: Boolean, secretDir: Path, lockingTimeout: Duration)

final case class NodeSetting(
    dbSyncWrite: Boolean,
    assetTrieCacheMaxByteSize: Int,
    contractTrieCacheMaxByteSize: Int,
    eventLog: LogConfig
) {
  def eventLogConfig: LogConfig = eventLog
}

final case class Allocation(
    address: Address.Asset,
    amount: Allocation.Amount,
    lockDuration: Duration
)
object Allocation {
  final case class Amount(value: U256)
  object Amount {
    def from(string: String): Option[Amount] =
      ALPH.alphFromString(string).map(Amount(_))
  }
}

final case class GenesisSetting(allocations: AVector[Allocation])

final case class AlephiumConfig(
    broker: BrokerSetting,
    consensus: ConsensusSettings,
    mining: MiningSetting,
    network: NetworkSetting,
    discovery: DiscoverySetting,
    mempool: MemPoolSetting,
    wallet: WalletSetting,
    node: NodeSetting,
    genesis: GenesisSetting
) {
  lazy val genesisBlocks: AVector[AVector[Block]] =
    Configs.loadBlockFlow(genesis.allocations)(
      broker,
      consensus,
      network
    )
}

object AlephiumConfig {
  import ConfigUtils._

  final private case class TempConsensusSettings(
      mainnet: TempConsensusSetting,
      rhone: TempConsensusSetting,
      blockCacheCapacityPerChain: Int,
      numZerosAtLeastInHash: Int
  ) {
    def toConsensusSettings(groupConfig: GroupConfig): ConsensusSettings = {
      val mainnetEmission = Emission.mainnet(groupConfig, mainnet.blockTargetTime)
      val rhoneEmission =
        Emission.rhone(groupConfig, mainnet.blockTargetTime, rhone.blockTargetTime)
      ConsensusSettings(
        mainnet.toConsensusSetting(mainnetEmission, numZerosAtLeastInHash),
        rhone.toConsensusSetting(rhoneEmission, numZerosAtLeastInHash),
        blockCacheCapacityPerChain
      )
    }
  }
  final private case class TempConsensusSetting(
      blockTargetTime: Duration,
      uncleDependencyGapTime: Duration
  ) {
    def toConsensusSetting(emission: Emission, numZerosAtLeastInHash: Int): ConsensusSetting = {
      ConsensusSetting(
        blockTargetTime,
        uncleDependencyGapTime,
        numZerosAtLeastInHash,
        emission
      )
    }
  }

  final private case class TempNetworkSetting(
      networkId: NetworkId,
      lemanHardForkTimestamp: TimeStamp,
      rhoneHardForkTimestamp: TimeStamp,
      noPreMineProof: Seq[String],
      maxOutboundConnectionsPerGroup: Int,
      maxInboundConnectionsPerGroup: Int,
      maxCliqueFromSameIp: Int,
      pingFrequency: Duration,
      retryTimeout: Duration,
      banDuration: Duration,
      penaltyForgiveness: Duration,
      penaltyFrequency: Duration,
      connectionBufferCapacityInByte: Long,
      backoffBaseDelay: Duration,
      backoffMaxDelay: Duration,
      backoffResetDelay: Duration,
      fastSyncFrequency: Duration,
      stableSyncFrequency: Duration,
      syncPeerSampleSize: Int,
      syncCleanupFrequency: Duration,
      syncExpiryPeriod: Duration,
      dependencyExpiryPeriod: Duration,
      updateSyncedFrequency: Duration,
      txsBroadcastDelay: Duration,
      upnp: UpnpSettings,
      bindAddress: InetSocketAddress,
      internalAddress: InetSocketAddress,
      coordinatorAddress: InetSocketAddress,
      externalAddress: Option[InetSocketAddress],
      restPort: Int,
      wsPort: Int,
      minerApiPort: Int
  ) {
    def toNetworkSetting(connectionBuild: ActorRef => ActorRefT[Tcp.Command]): NetworkSetting = {
      val proofInOne = Hash.doubleHash(ByteString.fromString(noPreMineProof.mkString(""))).bytes
      NetworkSetting(
        networkId,
        lemanHardForkTimestamp,
        rhoneHardForkTimestamp,
        proofInOne,
        maxOutboundConnectionsPerGroup,
        maxInboundConnectionsPerGroup,
        maxCliqueFromSameIp,
        pingFrequency,
        retryTimeout,
        banDuration: Duration,
        penaltyForgiveness: Duration,
        penaltyFrequency,
        backoffBaseDelay,
        backoffMaxDelay,
        backoffResetDelay,
        connectionBufferCapacityInByte,
        fastSyncFrequency,
        stableSyncFrequency,
        syncPeerSampleSize,
        syncCleanupFrequency,
        syncExpiryPeriod,
        dependencyExpiryPeriod,
        updateSyncedFrequency,
        txsBroadcastDelay,
        upnp,
        bindAddress,
        internalAddress,
        coordinatorAddress,
        externalAddress,
        restPort,
        wsPort,
        minerApiPort,
        connectionBuild
      )
    }
  }

  final private case class TempMiningSetting(
      minerAddresses: Option[Seq[String]],
      nonceStep: U256,
      batchDelay: Duration,
      apiInterface: InetAddress,
      pollingInterval: Duration,
      jobCacheSizePerChain: Int
  ) {
    def toMiningSetting(addresses: Option[AVector[Address.Asset]]): MiningSetting = {
      MiningSetting(
        addresses,
        nonceStep,
        batchDelay,
        apiInterface,
        pollingInterval,
        jobCacheSizePerChain
      )
    }
  }

  final private case class TempAlephiumConfig(
      broker: BrokerSetting,
      consensus: TempConsensusSettings,
      mining: TempMiningSetting,
      network: TempNetworkSetting,
      discovery: DiscoverySetting,
      mempool: MemPoolSetting,
      wallet: WalletSetting,
      node: NodeSetting,
      genesis: GenesisSetting
  ) {
    lazy val toAlephiumConfig: AlephiumConfig = {
      parseMiners(mining.minerAddresses)(broker).map { minerAddresses =>
        val consensusExtracted = consensus.toConsensusSettings(broker)
        val networkExtracted   = network.toNetworkSetting(ActorRefT.apply)
        val discoveryRefined = if (network.networkId == NetworkId.AlephiumTestNet) {
          if (discovery.bootstrap.isEmpty) {
            discovery.copy(bootstrap =
              ArraySeq(
                new InetSocketAddress("bootstrap0.testnet.alephium.org", 9973),
                new InetSocketAddress("bootstrap1.testnet.alephium.org", 9973)
              )
            )
          } else {
            discovery
          }
        } else {
          discovery
        }
        AlephiumConfig(
          broker,
          consensusExtracted,
          mining.toMiningSetting(minerAddresses),
          networkExtracted,
          discoveryRefined,
          mempool,
          wallet,
          node,
          genesis
        )
      } match {
        case Right(value) => value
        case Left(error)  => throw error
      }
    }
  }

  implicit val alephiumValueReader: ValueReader[AlephiumConfig] =
    valueReader { implicit cfg =>
      TempAlephiumConfig(
        as[BrokerSetting]("broker"),
        as[TempConsensusSettings]("consensus"),
        as[TempMiningSetting]("mining"),
        as[TempNetworkSetting]("network"),
        as[DiscoverySetting]("discovery"),
        as[MemPoolSetting]("mempool"),
        as[WalletSetting]("wallet"),
        as[NodeSetting]("node"),
        as[GenesisSetting]("genesis")
      ).toAlephiumConfig
    }

  def load(env: Env, rootPath: Path, configPath: String): AlephiumConfig =
    load(
      Configs.parseConfig(env, rootPath, overwrite = true, predefined = ConfigFactory.empty()),
      configPath
    )
  def load(rootPath: Path, configPath: String): AlephiumConfig =
    load(Env.currentEnv, rootPath, configPath)
  def load(config: Config, configPath: String): AlephiumConfig =
    sanityCheck(config.as[AlephiumConfig](configPath))
  def load(config: Config): AlephiumConfig = load(config, "alephium")

  def sanityCheck(config: AlephiumConfig): AlephiumConfig = {
    if (
      config.network.networkId == NetworkId.AlephiumMainNet &&
      config.network.lemanHardForkTimestamp != TimeStamp.unsafe(1680170400000L)
    ) {
      throw new IllegalArgumentException("Invalid timestamp for leman hard fork")
    }

    if (
      config.network.networkId == NetworkId.AlephiumMainNet &&
      config.network.rhoneHardForkTimestamp != TimeStamp.unsafe(1718186400000L)
    ) {
      throw new IllegalArgumentException("Invalid timestamp for rhone hard fork")
    }

    config
  }
}
