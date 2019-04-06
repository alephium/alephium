package org.alephium.flow

import java.time.Duration
import java.io.File
import java.net.{InetAddress, InetSocketAddress}
import java.nio.file.Path

import com.typesafe.config.{Config, ConfigFactory}
import com.typesafe.scalalogging.StrictLogging
import org.alephium.flow.io.{Database, Disk}
import org.alephium.flow.network.DiscoveryConfig
import org.alephium.protocol.config.{ConsensusConfig, GroupConfig}
import org.alephium.protocol.model.{Block, ChainIndex, GroupIndex, PeerId}
import org.alephium.util.{AVector, Env, Files, Network}
import org.rocksdb.Options

import scala.collection.JavaConverters._
import scala.concurrent.duration._

object PlatformConfig extends StrictLogging {
  private val env = Env.resolve()
  private val rootPath = {
    env match {
      case Env.Prod =>
        Files.homeDir.resolve(".alephium")
      case Env.Debug =>
        Files.homeDir.resolve(s".alephium-${env.name}")
      case Env.Test =>
        Files.tmpDir.resolve(s".alephium-${env.name}")
    }
  }

  object Default extends PlatformConfig(env, rootPath)

  trait Default {
    implicit def config: PlatformConfig = Default
  }
}

trait PlatformConfigFiles extends StrictLogging {
  def env: Env
  def rootPath: Path

  def getNoncesPath(groups: Int, leadingZeros: Int): Path =
    rootPath.resolve(s"nonces-$groups-$leadingZeros.conf")

  def getNoncesFile(groups: Int, leadingZeros: Int): File = {
    val path = getNoncesPath(groups, leadingZeros)
    val file = path.toFile
    if (!file.exists()) {
      logger.error(s"Nonces file not found at $path")
      System.exit(1)
    }
    file
  }

  def getUserFile(): File = {
    val directory = rootPath.toFile
    if (!directory.exists) directory.mkdir()

    val env      = Env.resolve()
    val filename = s"user_${env.name}.conf"
    val path     = rootPath.resolve("user.conf")
    logger.info(s"Using configuration file at $path")

    val file = path.toFile
    if (!file.exists) Files.copyFromResource(s"/$filename.tmpl", path)
    file
  }

  Disk.createDirUnsafe(rootPath)
  val all      = ConfigFactory.parseFile(getUserFile).resolve()
  val alephium = all.getConfig("alephium")
}

trait PlatformGroupConfig extends PlatformConfigFiles with GroupConfig {
  val groups: Int = alephium.getInt("groups")

  val mainGroup: GroupIndex = {
    val myGroup = alephium.getInt("mainGroup")
    assert(myGroup >= 0 && myGroup < groups)
    GroupIndex(myGroup)(this)
  }
}

trait PlatformConsensusConfig extends PlatformGroupConfig with ConsensusConfig {
  protected def alephium: Config

  val numZerosAtLeastInHash: Int = alephium.getInt("numZerosAtLeastInHash")
  val maxMiningTarget: BigInt    = (BigInt(1) << (256 - numZerosAtLeastInHash)) - 1

  val blockTargetTime: Duration = alephium.getDuration("blockTargetTime")
  val blockConfirmNum: Int      = alephium.getInt("blockConfirmNum")
  val retargetInterval: Int     = alephium.getInt("retargetInterval") // number of blocks for retarget
  val expectedTimeSpan: Long    = retargetInterval * blockTargetTime.toMillis
}

trait PlatformGenesisConfig extends PlatformConfigFiles with PlatformConsensusConfig {
  def loadNonces(): AVector[BigInt] = {
    val noncesConfig = env match {
      case Env.Test => all
      case _ =>
        val noncesFile = getNoncesFile(groups, numZerosAtLeastInHash)
        ConfigFactory.parseFile(noncesFile).resolve()
    }
    val nonces = noncesConfig.getStringList("nonces").asScala
    AVector.from(nonces.map(BigInt.apply))
  }

  def loadBlockFlow(): AVector[AVector[Block]] = {
    val nonces = loadNonces()
    assert(nonces.length == groups * groups)

    AVector.tabulate(groups, groups) {
      case (from, to) =>
        val index      = from * groups + to
        val nonce      = nonces(index)
        val block      = Block.genesis(AVector.empty, maxMiningTarget, nonce)
        val chainIndex = ChainIndex(from, to)(this)
        assert(chainIndex.validateDiff(block)(this))
        block
    }
  }

  lazy val genesisBlocks: AVector[AVector[Block]] = loadBlockFlow()
}

trait PDiscoveryConfig extends PlatformGroupConfig {
  lazy val discoveryConfig: DiscoveryConfig = loadDiscoveryConfig()
  lazy val bootstrap: AVector[InetSocketAddress] =
    Network.parseAddresses(alephium.getString("bootstrap"))
  def nodeId: PeerId = discoveryConfig.nodeId

  def loadDiscoveryConfig(): DiscoveryConfig = {
    val discovery = alephium.getConfig("discovery").resolve()
    discovery.resolve()
    val publicAddress           = InetAddress.getByName(discovery.getString("publicAddress"))
    val udpPort                 = discovery.getInt("udpPort")
    val peersPerGroup           = discovery.getInt("peersPerGroup")
    val scanMaxPerGroup         = discovery.getInt("scanMaxPerGroup")
    val scanFrequency           = discovery.getDuration("scanFrequency").toMillis.millis
    val scanFastFrequency       = discovery.getDuration("scanFastFrequency").toMillis.millis
    val neighborsPerGroup       = discovery.getInt("neighborsPerGroup")
    val (privateKey, publicKey) = GroupConfig.generateKeyForGroup(mainGroup)(this)
    DiscoveryConfig(publicAddress,
                    udpPort,
                    groups,
                    privateKey,
                    publicKey,
                    peersPerGroup,
                    scanMaxPerGroup,
                    scanFrequency,
                    scanFastFrequency,
                    neighborsPerGroup)
  }

}

class PlatformConfig(val env: Env, val rootPath: Path)
    extends PlatformGroupConfig
    with PlatformConsensusConfig
    with PlatformGenesisConfig
    with PDiscoveryConfig { self =>

  val port: Int                     = alephium.getInt("port")
  val pingFrequency: FiniteDuration = getDuration("pingFrequency")
  val nonceStep: BigInt             = alephium.getInt("nonceStep")
  val retryTimeout: FiniteDuration  = getDuration("retryTimeout")

  def getDuration(path: String): FiniteDuration = {
    val duration = alephium.getDuration(path)
    FiniteDuration(duration.toNanos, NANOSECONDS)
  }

  val disk: Disk = Disk.createUnsafe(rootPath)

  val dbPath = rootPath.resolve("db")
  val db: Database = {
    Disk.createDirUnsafe(dbPath)
    val dbName = "all-" + nodeId.shortHex
    Database.openUnsafe(dbPath.resolve(dbName), new Options().setCreateIfMissing(true))
  }
}
