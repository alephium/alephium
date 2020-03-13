package org.alephium.flow.platform

import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Path

import com.typesafe.config.{Config, ConfigFactory}
import com.typesafe.scalalogging.StrictLogging

import org.alephium.protocol.config.{DiscoveryConfig => DC, _}
import org.alephium.protocol.model._
import org.alephium.util._

trait Configs
    extends Configs.PlatformGroupConfig
    with Configs.PlatformCliqueConfig
    with Configs.PlatformConsensusConfig
    with Configs.PlatformDiscoveryConfig
    with Configs.PlatformBrokerConfig
    with Configs.PlatformGenesisConfig
    with Configs.PlatformMiningConfig
    with Configs.PlatformNetworkConfig

@SuppressWarnings(Array("org.wartremover.warts.OptionPartial"))
object Configs extends StrictLogging {
  trait PlatformCommonConfig { def rootPath: Path }
  trait PlatformGroupConfig  extends GroupConfig
  trait PlatformCliqueConfig extends CliqueConfig
  trait PlatformConsensusConfig extends ConsensusConfig {
    def expectedTimeSpan: Duration

    def blockCacheSize: Int

    // Digi Shields Difficulty Adjustment
    def medianTimeInterval: Int
    def diffAdjustDownMax: Int
    def diffAdjustUpMax: Int
    def timeSpanMin: Duration
    def timeSpanMax: Duration
  }
  trait PlatformDiscoveryConfig extends DC
  trait PlatformBrokerConfig { def brokerInfo: BrokerInfo }
  trait PlatformMiningConfig { def nonceStep: BigInt }
  trait PlatformNetworkConfig {
    def pingFrequency: Duration
    def retryTimeout: Duration
    def publicAddress: InetSocketAddress
    def masterAddress: InetSocketAddress
    def numOfSyncBlocksLimit: Int
    def isCoordinator: Boolean

    def bootstrap: AVector[InetSocketAddress]

    def rpcPort: Option[Int]
    def wsPort: Option[Int]
  }
  trait PlatformGenesisConfig { def genesisBlocks: AVector[AVector[Block]] }

  def parseAddress(s: String): InetSocketAddress = {
    val List(left, right) = s.split(':').toList
    new InetSocketAddress(left, right.toInt)
  }

  def validatePort(port: Int): Option[Int] = {
    if (port > 0x0400 && port <= 0xFFFF) Some(port) else None
  }

  def getDuration(config: Config, path: String): Duration = {
    val duration = config.getDuration(path)
    Duration.from(duration).get
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
