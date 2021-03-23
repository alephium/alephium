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
import pureconfig.{ConfigReader, ConfigSource}
import pureconfig.ConfigReader.Result
import pureconfig.error._
import pureconfig.generic.auto._

import org.alephium.flow.network.nat.Upnp
import org.alephium.protocol.SignatureSchema
import org.alephium.protocol.config._
import org.alephium.protocol.mining.Emission
import org.alephium.protocol.model.{Block, NetworkType, Target}
import org.alephium.protocol.vm.LockupScript
import org.alephium.util.{ActorRefT, AVector, Duration, U256}

final case class BrokerSetting(groups: Int, brokerNum: Int, brokerId: Int) extends BrokerConfig {
  override lazy val groupNumPerBroker: Int = groups / brokerNum
}

//scalastyle:off magic.number
@SuppressWarnings(Array("org.wartremover.warts.OptionPartial"))
final case class ConsensusSetting(
    blockTargetTime: Duration,
    numZerosAtLeastInHash: Int,
    tipsPruneInterval: Int,
    blockCacheCapacityPerChain: Int,
    emission: Emission
) extends ConsensusConfig {
  val maxMiningTarget: Target =
    Target.unsafe(BigInteger.ONE.shiftLeft(256 - numZerosAtLeastInHash).subtract(BigInteger.ONE))

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
    pingFrequency: Duration,
    retryTimeout: Duration,
    connectionBufferCapacityInByte: Long,
    upnp: UpnpSettings,
    bindAddress: InetSocketAddress,
    internalAddress: InetSocketAddress,
    coordinatorAddress: InetSocketAddress,
    externalAddress: Option[InetSocketAddress],
    numOfSyncBlocksLimit: Int,
    wsPort: Int,
    restPort: Int,
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
    peersPerGroup: Int,
    scanMaxPerGroup: Int,
    scanFrequency: Duration,
    scanFastFrequency: Duration,
    neighborsPerGroup: Int
) extends DiscoveryConfig {
  val (discoveryPrivateKey, discoveryPublicKey) = SignatureSchema.generatePriPub()
}

final case class MemPoolSetting(txPoolCapacity: Int, txMaxNumberPerBlock: Int)

final case class WalletSetting(port: Int, secretDir: Path, lockingTimeout: Duration)

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
    minerAddresses: AVector[LockupScript]
) {
  lazy val genesisBlocks: AVector[AVector[Block]] =
    Configs.loadBlockFlow(genesisBalances)(broker, consensus)
}
object AlephiumConfig {
  import PureConfigUtils._

  final private case class TempConsensusSetting(
      blockTargetTime: Duration,
      numZerosAtLeastInHash: Int,
      tipsPruneInterval: Int,
      blockCacheCapacityPerChain: Int
  ) {
    def toConsensusSetting(groupConfig: GroupConfig): ConsensusSetting = {
      val emission = Emission(groupConfig, blockTargetTime)
      ConsensusSetting(
        blockTargetTime,
        numZerosAtLeastInHash,
        tipsPruneInterval,
        blockCacheCapacityPerChain,
        emission
      )
    }
  }

  final private case class TempNetworkSetting(
      networkType: NetworkType,
      pingFrequency: Duration,
      retryTimeout: Duration,
      connectionBufferCapacityInByte: Long,
      upnp: UpnpSettings,
      bindAddress: InetSocketAddress,
      internalAddress: InetSocketAddress,
      coordinatorAddress: InetSocketAddress,
      externalAddress: Option[InetSocketAddress],
      numOfSyncBlocksLimit: Int,
      wsPort: Int,
      restPort: Int
  ) {
    def toNetworkSetting(connectionBuild: ActorRef => ActorRefT[Tcp.Command]): NetworkSetting = {
      NetworkSetting(
        networkType,
        pingFrequency,
        retryTimeout,
        connectionBufferCapacityInByte,
        upnp,
        bindAddress,
        internalAddress,
        coordinatorAddress,
        externalAddress,
        numOfSyncBlocksLimit,
        wsPort,
        restPort,
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
    lazy val toAlephiumConfig: Either[FailureReason, AlephiumConfig] = {
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
      }
    }
  }

  implicit val alephiumConfigReader: ConfigReader[AlephiumConfig] =
    ConfigReader[TempAlephiumConfig].emap(_.toAlephiumConfig)

  def source(config: Config): ConfigSource = {
    val path          = "alephium"
    val configLocated = if (config.hasPath(path)) config.getConfig(path) else config
    ConfigSource.fromConfig(configLocated)
  }

  def load(rootPath: Path): Result[AlephiumConfig] = load(Configs.parseConfig(rootPath))
  def load(config: Config): Result[AlephiumConfig] = source(config).load[AlephiumConfig]
  def loadOrThrow(config: Config): AlephiumConfig  = source(config).loadOrThrow[AlephiumConfig]
}
