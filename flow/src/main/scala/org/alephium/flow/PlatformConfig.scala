package org.alephium.flow

import java.time.Duration
import java.io.File
import java.net.{InetAddress, InetSocketAddress}
import java.nio.file.Path

import com.typesafe.config.{Config, ConfigFactory}
import com.typesafe.scalalogging.StrictLogging
import org.alephium.flow.io.{Disk, HeaderDB}
import org.alephium.flow.network.DiscoveryConfig
import org.alephium.flow.trie.MerklePatriciaTrie
import org.alephium.protocol.config.{ConsensusConfig, GroupConfig}
import org.alephium.protocol.model.{Block, ChainIndex, GroupIndex, PeerId}
import org.alephium.util.{AVector, Env, Files, Network}
import org.rocksdb.Options

import scala.annotation.tailrec
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

  def mineGenesis(chainIndex: ChainIndex)(implicit config: ConsensusConfig): Block = {
    @tailrec
    def iter(nonce: BigInt): Block = {
      val block = Block.genesis(AVector.empty, config.maxMiningTarget, nonce)
      // Note: we do not validate difficulty target here
      if (block.validateIndex(chainIndex)) block else iter(nonce + 1)
    }

    iter(0)
  }
}

trait PlatformConfigFiles extends StrictLogging {
  def env: Env
  def rootPath: Path

  def getConfigFile[A](name: String)(create: File => A): File = {
    val directory = rootPath.toFile
    if (!directory.exists) directory.mkdir()

    val path = rootPath.resolve(s"$name.conf")
    logger.info(s"Using $name configuration file at $path")

    val file = path.toFile
    if (!file.exists) {
      create(file)
    }
    file
  }

  def getConfigSystem(): File =
    getConfigFile("system") { file =>
      val env      = Env.resolve()
      val filename = s"system_${env.name}.conf"
      Files.copyFromResource(s"/$filename.tmpl", file.toPath)
      file.setWritable(false)
    }

  def getConfigUser(): File =
    getConfigFile("user")(_.createNewFile)

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

  def parseConfig(): Config = {
    ConfigFactory
      .parseFile(getConfigUser)
      .withFallback(ConfigFactory.parseFile(getConfigSystem))
      .resolve()
  }

  Disk.createDirUnsafe(rootPath)
  val all      = parseConfig()
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
  val expectedTimeSpan: Long    = blockTargetTime.toMillis

  // Digi Shields Difficulty Adjustment
  val medianTimeInterval = 11
  val diffAdjustDownMax  = 16
  val diffAdjustUpMax    = 8
  val timeSpanMin: Long  = expectedTimeSpan * (100 - diffAdjustDownMax) / 100
  val timeSpanMax: Long  = expectedTimeSpan * (100 + diffAdjustUpMax) / 100
}

trait PlatformGenesisConfig extends PlatformConfigFiles with PlatformConsensusConfig {
  def loadBlockFlow(): AVector[AVector[Block]] = {
    AVector.tabulate(groups, groups) {
      case (from, to) =>
        PlatformConfig.mineGenesis(ChainIndex(from, to)(this))(this)
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

  val dbPath = {
    val path = rootPath.resolve("db")
    Disk.createDirUnsafe(path)
    path
  }
  val headerDB: HeaderDB = {
    val dbName = "all-" + nodeId.shortHex
    HeaderDB.openUnsafe(dbPath.resolve(dbName), new Options().setCreateIfMissing(true))
  }
  val trie: MerklePatriciaTrie = {
    val dbName = "trie-" + nodeId.shortHex
    val storage =
      HeaderDB.openUnsafe(dbPath.resolve(dbName), new Options().setCreateIfMissing(true))
    MerklePatriciaTrie.create(storage)
  }
}
