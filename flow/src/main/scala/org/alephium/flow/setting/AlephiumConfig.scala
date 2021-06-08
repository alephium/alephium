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
import java.nio.file.Path

import scala.collection.immutable.ArraySeq

import akka.actor.ActorRef
import akka.io.Tcp
import com.typesafe.config.Config
import net.ceedubs.ficus.Ficus._
import net.ceedubs.ficus.readers.ArbitraryTypeReader._
import net.ceedubs.ficus.readers.ValueReader

import org.alephium.conf._
import org.alephium.flow.network.nat.Upnp
import org.alephium.protocol.config._
import org.alephium.protocol.mining.Emission
import org.alephium.protocol.model.{Address, Block, NetworkType, Target, Weight}
import org.alephium.protocol.vm.LockupScript
import org.alephium.util.{ActorRefT, AVector, Duration, U256}

final case class BrokerSetting(groups: Int, brokerNum: Int, brokerId: Int) extends BrokerConfig {
  override lazy val groupNumPerBroker: Int = groups / brokerNum
}

//scalastyle:off magic.number
@SuppressWarnings(Array("org.wartremover.warts.OptionPartial"))
final case class ConsensusSetting(
    blockTargetTime: Duration,
    uncleDependencyGapTime: Duration,
    numZerosAtLeastInHash: Int,
    tipsPruneInterval: Int,
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

  val tipsPruneDuration: Duration         = blockTargetTime.timesUnsafe(tipsPruneInterval.toLong)
  val conflictCacheKeepDuration: Duration = expectedTimeSpan timesUnsafe 20
}
//scalastyle:on

final case class MiningSetting(nonceStep: U256, batchDelay: Duration)

final case class NetworkSetting(
    networkType: NetworkType,
    maxOutboundConnectionsPerGroup: Int,
    maxInboundConnectionsPerGroup: Int,
    pingFrequency: Duration,
    retryTimeout: Duration,
    banDuration: Duration,
    penaltyForgiveness: Duration,
    penaltyFrequency: Duration,
    connectionBufferCapacityInByte: Long,
    upnp: UpnpSettings,
    bindAddress: InetSocketAddress,
    internalAddress: InetSocketAddress,
    coordinatorAddress: InetSocketAddress,
    externalAddress: Option[InetSocketAddress],
    restPort: Int,
    wsPort: Int,
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
    neighborsPerGroup: Int
) extends DiscoveryConfig

final case class MemPoolSetting(txPoolCapacity: Int, txMaxNumberPerBlock: Int)

final case class WalletSetting(secretDir: Path, lockingTimeout: Duration)

object WalletSetting {
  final case class BlockFlow(host: String, port: Int, groups: Int)
}

final case class AlephiumConfig(
    broker: BrokerSetting,
    consensus: ConsensusSetting,
    mining: MiningSetting,
    network: NetworkSetting,
    discovery: DiscoverySetting,
    mempool: MemPoolSetting,
    wallet: WalletSetting,
    genesisBalances: AVector[(LockupScript, U256)],
    minerAddresses: AVector[Address]
) {
  lazy val genesisBlocks: AVector[AVector[Block]] =
    Configs.loadBlockFlow(genesisBalances)(broker, consensus)
}
object AlephiumConfig {
  import ConfigUtils._

  final private case class TempConsensusSetting(
      blockTargetTime: Duration,
      uncleDependencyGapTime: Option[Duration],
      numZerosAtLeastInHash: Int,
      tipsPruneInterval: Int,
      blockCacheCapacityPerChain: Int
  ) {
    def toConsensusSetting(groupConfig: GroupConfig): ConsensusSetting = {
      val emission = Emission(groupConfig, blockTargetTime)
      ConsensusSetting(
        blockTargetTime,
        uncleDependencyGapTime.getOrElse(blockTargetTime),
        numZerosAtLeastInHash,
        tipsPruneInterval,
        blockCacheCapacityPerChain,
        emission
      )
    }
  }

  final private case class TempNetworkSetting(
      networkType: NetworkType,
      maxOutboundConnectionsPerGroup: Int,
      maxInboundConnectionsPerGroup: Int,
      pingFrequency: Duration,
      retryTimeout: Duration,
      banDuration: Duration,
      penaltyForgiveness: Duration,
      penaltyFrequency: Duration,
      connectionBufferCapacityInByte: Long,
      upnp: UpnpSettings,
      bindAddress: InetSocketAddress,
      internalAddress: InetSocketAddress,
      coordinatorAddress: InetSocketAddress,
      externalAddress: Option[InetSocketAddress],
      restPort: Int,
      wsPort: Int
  ) {
    def toNetworkSetting(connectionBuild: ActorRef => ActorRefT[Tcp.Command]): NetworkSetting = {
      NetworkSetting(
        networkType,
        maxOutboundConnectionsPerGroup,
        maxInboundConnectionsPerGroup,
        pingFrequency,
        retryTimeout,
        banDuration: Duration,
        penaltyForgiveness: Duration,
        penaltyFrequency,
        connectionBufferCapacityInByte,
        upnp,
        bindAddress,
        internalAddress,
        coordinatorAddress,
        externalAddress,
        restPort,
        wsPort,
        connectionBuild
      )
    }
  }

  final private case class TempAlephiumConfig(
      broker: BrokerSetting,
      consensus: TempConsensusSetting,
      mining: MiningSetting,
      network: TempNetworkSetting,
      discovery: DiscoverySetting,
      mempool: MemPoolSetting,
      wallet: WalletSetting,
      minerAddresses: Option[Seq[String]]
  ) {
    lazy val toAlephiumConfig: AlephiumConfig = {
      parseMiners(minerAddresses, network.networkType, broker).map { minerAddresses =>
        val consensusExtracted = consensus.toConsensusSetting(broker)
        val networkExtracted   = network.toNetworkSetting(ActorRefT.apply)
        AlephiumConfig(
          broker,
          consensusExtracted,
          mining,
          networkExtracted,
          discovery,
          mempool,
          wallet,
          Genesis(network.networkType),
          minerAddresses
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
        as[MiningSetting]("mining"),
        as[TempNetworkSetting]("network"),
        as[DiscoverySetting]("discovery"),
        as[MemPoolSetting]("mempool"),
        as[WalletSetting]("wallet"),
        as[Option[Seq[String]]]("minerAddresses")
      ).toAlephiumConfig
    }

  def load(rootPath: Path, path: String): AlephiumConfig = load(Configs.parseConfig(rootPath), path)
  def load(config: Config, path: String): AlephiumConfig = config.as[AlephiumConfig](path)
  def load(config: Config): AlephiumConfig               = config.as[AlephiumConfig]("alephium")
}
