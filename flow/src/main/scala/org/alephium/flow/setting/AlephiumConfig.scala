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
import java.util.{GregorianCalendar, TimeZone}

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
import org.alephium.protocol.model.{Address, Block, NetworkId, Target, Weight}
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
    blockCacheCapacityPerChain: Int,
    emission: Emission
) extends ConsensusConfig {
  val maxMiningTarget: Target =
    Target.unsafe(BigInteger.ONE.shiftLeft(256 - numZerosAtLeastInHash).subtract(BigInteger.ONE))
  val minBlockWeight: Weight = Weight.from(maxMiningTarget)

  val expectedTimeSpan: Duration       = blockTargetTime
  val powAveragingWindow: Int          = 17
  val expectedWindowTimeSpan: Duration = expectedTimeSpan.timesUnsafe(powAveragingWindow.toLong)

  val diffAdjustDownMax: Int = 16
  val diffAdjustUpMax: Int   = 8
  val windowTimeSpanMin: Duration =
    (expectedWindowTimeSpan * (100L - diffAdjustDownMax)).get divUnsafe 100L
  val windowTimeSpanMax: Duration =
    (expectedWindowTimeSpan * (100L + diffAdjustUpMax)).get divUnsafe 100L

  val recentBlockHeightDiff: Int         = 30
  val recentBlockTimestampDiff: Duration = Duration.ofMinutesUnsafe(30)

  val tipsPruneDuration: Duration = blockTargetTime.timesUnsafe(maxForkDepth.toLong)
  val conflictCacheKeepDuration: Duration =
    expectedTimeSpan timesUnsafe blockCacheCapacityPerChain.toLong
}
//scalastyle:on

final case class MiningSetting(
    minerAddresses: Option[AVector[Address.Asset]],
    nonceStep: U256,
    batchDelay: Duration,
    apiInterface: InetAddress,
    pollingInterval: Duration
)

final case class NetworkSetting(
    networkId: NetworkId,
    lemanHardForkTimestamp: TimeStamp,
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
    sharedPoolCapacity: Int,
    pendingPoolCapacity: Int,
    txMaxNumberPerBlock: Int,
    cleanSharedPoolFrequency: Duration,
    cleanPendingPoolFrequency: Duration,
    batchBroadcastTxsFrequency: Duration,
    batchDownloadTxsFrequency: Duration,
    autoMineForDev: Boolean // for dev only
)

final case class WalletSetting(secretDir: Path, lockingTimeout: Duration)

object WalletSetting {
  final case class BlockFlow(host: String, port: Int, groups: Int)
}

final case class NodeSetting(
    dbSyncWrite: Boolean,
    eventLog: Option[LogConfig]
) {
  lazy val logConfig: LogConfig = {
    eventLog.getOrElse(LogConfig.disabled())
  }
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

final case class CompilerSetting(loopUnrollingLimit: Int) extends CompilerConfig

final case class AlephiumConfig(
    broker: BrokerSetting,
    consensus: ConsensusSetting,
    mining: MiningSetting,
    network: NetworkSetting,
    discovery: DiscoverySetting,
    mempool: MemPoolSetting,
    wallet: WalletSetting,
    node: NodeSetting,
    genesis: GenesisSetting,
    compiler: CompilerSetting
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

  final private case class TempConsensusSetting(
      blockTargetTime: Duration,
      uncleDependencyGapTime: Option[Duration],
      numZerosAtLeastInHash: Int,
      blockCacheCapacityPerChain: Int
  ) {
    def toConsensusSetting(groupConfig: GroupConfig): ConsensusSetting = {
      val emission = Emission(groupConfig, blockTargetTime)
      ConsensusSetting(
        blockTargetTime,
        uncleDependencyGapTime.getOrElse(blockTargetTime.divUnsafe(4)),
        numZerosAtLeastInHash,
        blockCacheCapacityPerChain,
        emission
      )
    }
  }

  final private case class TempNetworkSetting(
      networkId: NetworkId,
      lemanHardForkTimestamp: TimeStamp,
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
      pollingInterval: Duration
  ) {
    def toMiningSetting(addresses: Option[AVector[Address.Asset]]): MiningSetting = {
      MiningSetting(
        addresses,
        nonceStep,
        batchDelay,
        apiInterface,
        pollingInterval
      )
    }
  }

  final private case class TempAlephiumConfig(
      broker: BrokerSetting,
      consensus: TempConsensusSetting,
      mining: TempMiningSetting,
      network: TempNetworkSetting,
      discovery: DiscoverySetting,
      mempool: MemPoolSetting,
      wallet: WalletSetting,
      node: NodeSetting,
      genesis: GenesisSetting,
      compiler: CompilerSetting
  ) {
    lazy val toAlephiumConfig: AlephiumConfig = {
      parseMiners(mining.minerAddresses)(broker).map { minerAddresses =>
        val consensusExtracted = consensus.toConsensusSetting(broker)
        val networkExtracted   = network.toNetworkSetting(ActorRefT.apply)
        AlephiumConfig(
          broker,
          consensusExtracted,
          mining.toMiningSetting(minerAddresses),
          networkExtracted,
          discovery,
          mempool,
          wallet,
          node,
          genesis,
          compiler
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
        as[TempConsensusSetting]("consensus"),
        as[TempMiningSetting]("mining"),
        as[TempNetworkSetting]("network"),
        as[DiscoverySetting]("discovery"),
        as[MemPoolSetting]("mempool"),
        as[WalletSetting]("wallet"),
        as[NodeSetting]("node"),
        as[GenesisSetting]("genesis"),
        as[CompilerSetting]("compiler")
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
    if (config.network.networkId == NetworkId.AlephiumMainNet) {
      val calendar = new GregorianCalendar(2022, 4, 30)
      calendar.setTimeZone(TimeZone.getTimeZone("UTC"))
      if (config.network.lemanHardForkTimestamp.millis != calendar.getTimeInMillis) {
        throw new IllegalArgumentException("Invalid timestamp for leman hard fork")
      }
    }

    config
  }
}
