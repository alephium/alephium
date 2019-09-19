package org.alephium.flow.platform

import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Path

import scala.concurrent.duration._

import com.typesafe.config.{Config, ConfigFactory}
import com.typesafe.scalalogging.StrictLogging

import org.alephium.protocol.config.{CliqueConfig, ConsensusConfig, DiscoveryConfig => DC, GroupConfig}
import org.alephium.protocol.model._
import org.alephium.util._

trait NewConfig
    extends NewConfig.PlatformGroupConfig
    with NewConfig.PlatformCliqueConfig
    with NewConfig.PlatformConsensusConfig
    with NewConfig.PlatformDiscoveryConfig
    with NewConfig.PlatformBrokerConfig
    with NewConfig.PlatformMiningConfig
    with NewConfig.PlatformNetworkConfig

object NewConfig extends StrictLogging {
  trait PlatformCommonConfig { def rootPath: Path }
  trait PlatformGroupConfig  extends GroupConfig
  trait PlatformCliqueConfig extends CliqueConfig
  trait PlatformConsensusConfig extends ConsensusConfig {
    def expectedTimeSpan: Long

    def blockCacheSize: Int

    // Digi Shields Difficulty Adjustment
    def medianTimeInterval: Int
    def diffAdjustDownMax: Int
    def diffAdjustUpMax: Int
    def timeSpanMin: Long
    def timeSpanMax: Long
  }
  trait PlatformDiscoveryConfig extends DC
  trait PlatformBrokerConfig { def brokerInfo: BrokerInfo }
  trait PlatformMiningConfig { def nonceStep: BigInt }
  trait PlatformNetworkConfig {
    def pingFrequency: FiniteDuration
    def retryTimeout: FiniteDuration
    def publicAddress: InetSocketAddress
    def masterAddress: InetSocketAddress
    def isCoordinator: Boolean

    def bootstrap: AVector[InetSocketAddress]
  }
  trait PlatformGenesisConfig { def genesisBlocks: AVector[AVector[Block]] }

  def parseAddress(s: String): InetSocketAddress = {
    val List(left, right) = s.split(':').toList
    new InetSocketAddress(left, right.toInt)
  }

  def getDuration(config: Config, path: String): FiniteDuration = {
    val duration = config.getDuration(path)
    FiniteDuration(duration.toNanos, NANOSECONDS)
  }

  def getConfigFile(rootPath: Path, name: String): File = {
    val directory = rootPath.toFile
    if (!directory.exists) directory.mkdir()

    val path = rootPath.resolve(s"$name.conf")
    logger.info(s"Using $name configuration file at $path \n")

    path.toFile
  }

  def getConfigSystem(rootPath: Path): File = {
    val file     = getConfigFile(rootPath, "system")
    val env      = Env.resolve()
    val filename = s"system_${env.name}.conf"
    if (!file.exists) {
      Files.copyFromResource(s"/$filename.tmpl", file.toPath)
      file.setWritable(false)
    }
    file
  }

  def getConfigUser(rootPath: Path): File = {
    val file = getConfigFile(rootPath, "user")
    if (!file.exists) { file.createNewFile }
    file
  }

  def parseConfig(rootPath: Path): Config = {
    ConfigFactory
      .parseFile(getConfigUser(rootPath))
      .withFallback(ConfigFactory.parseFile(getConfigSystem(rootPath)))
      .resolve()
  }
}
