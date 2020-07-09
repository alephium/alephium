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
    extends Configs.PlatformCommonConfig
    with Configs.PlatformGroupConfig
    with Configs.PlatformCliqueConfig
    with Configs.PlatformConsensusConfig
    with Configs.PlatformDiscoveryConfig
    with Configs.PlatformBrokerConfig
    with Configs.PlatformGenesisConfig
    with Configs.PlatformMiningConfig
    with Configs.PlatformNetworkConfig
    with Configs.PlatformScriptConfig

@SuppressWarnings(Array("org.wartremover.warts.OptionPartial"))
object Configs extends StrictLogging {
  trait PlatformCommonConfig { def rootPath: Path }
  trait PlatformGroupConfig  extends GroupConfig
  trait PlatformCliqueConfig extends CliqueConfig
  trait PlatformConsensusConfig extends ConsensusConfig {
    def expectedTimeSpan: Duration

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
    def restPort: Option[Int]
  }
  trait PlatformGenesisConfig { def genesisBlocks: AVector[AVector[Block]] }
  trait PlatformScriptConfig extends ScriptConfig { def maxStackSize: Int }

  def parseAddress(s: String): InetSocketAddress = {
    val List(left, right) = s.split(':').toList
    new InetSocketAddress(left, right.toInt)
  }

  private def check(port: Int): Boolean = {
    port > 0x0400 && port <= 0xFFFF
  }

  def extractPort(port: Int): Option[Int] = {
    if (port == 0) None
    else if (check(port)) Some(port)
    else throw new RuntimeException(s"Invalid port: $port")
  }

  def validatePort(port: Int): Either[String, Unit] = {
    if (check(port)) Right(()) else Left(s"Invalid port: $port")
  }

  def validatePort(portOpt: Option[Int]): Either[String, Unit] = {
    portOpt match {
      case Some(port) => validatePort(port)
      case None       => Right(())
    }
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
