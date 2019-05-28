package org.alephium.flow

import java.time.Duration
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Path

import com.typesafe.config.{Config, ConfigFactory}
import com.typesafe.scalalogging.StrictLogging
<<<<<<< HEAD
import org.alephium.crypto.ED25519
import org.alephium.flow.io.{Disk, HeaderDB}
=======
import org.alephium.flow.io.{Disk, HeaderDB, RocksDBStorage}
import org.alephium.flow.network.DiscoveryConfig
>>>>>>> f740202... Introduce default parameters for RocksDB.
import org.alephium.flow.trie.MerklePatriciaTrie
import org.alephium.protocol.config.{BrokerConfig, CliqueConfig, ConsensusConfig, GroupConfig, DiscoveryConfig => DC}
import org.alephium.protocol.model._
import org.alephium.util.{AVector, Env, Files, Network}

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

  def parseConfig(): Config = {
    ConfigFactory
      .parseFile(getConfigUser)
      .withFallback(ConfigFactory.parseFile(getConfigSystem))
      .resolve()
  }

  Disk.createDirUnsafe(rootPath)
  val all      = parseConfig()
  val alephium = all.getConfig("alephium")

  def getDuration(config: Config, path: String): FiniteDuration = {
    val duration = config.getDuration(path)
    FiniteDuration(duration.toNanos, NANOSECONDS)
  }
}

trait PlatformGroupConfig extends PlatformConfigFiles with GroupConfig {
  val groups: Int = alephium.getInt("groups")
}

trait PlatformCliqueConfig extends PlatformConfigFiles with CliqueConfig {
  def cliqueConfigRaw: Config = alephium.getConfig("clique").resolve()

  val brokerNum: Int = cliqueConfigRaw.getInt("brokerNum")
  require(groups % brokerNum == 0)
  val groupNumPerBroker: Int = groups / brokerNum
}

trait PlatformBrokerConfig extends PlatformConfigFiles with BrokerConfig {
  def brokerConfigRaw: Config = alephium.getConfig("broker").resolve()

  val brokerId: BrokerId = {
    val myId = brokerConfigRaw.getInt("brokerId")
    assert(0 <= myId && myId * groupNumPerBroker < groups)
    BrokerId(myId)(this)
  }
}

trait PlatformConsensusConfig extends PlatformConfigFiles with ConsensusConfig {
  def consensusConfigRaw: Config = alephium.getConfig("consensus").resolve

  val numZerosAtLeastInHash: Int = consensusConfigRaw.getInt("numZerosAtLeastInHash")
  val maxMiningTarget: BigInt    = (BigInt(1) << (256 - numZerosAtLeastInHash)) - 1

  val blockTargetTime: Duration = consensusConfigRaw.getDuration("blockTargetTime")
  val blockConfirmNum: Int      = consensusConfigRaw.getInt("blockConfirmNum")
  val expectedTimeSpan: Long    = blockTargetTime.toMillis

  // Digi Shields Difficulty Adjustment
  val medianTimeInterval = 11
  val diffAdjustDownMax  = 16
  val diffAdjustUpMax    = 8
  val timeSpanMin: Long  = expectedTimeSpan * (100 - diffAdjustDownMax) / 100
  val timeSpanMax: Long  = expectedTimeSpan * (100 + diffAdjustUpMax) / 100
}

trait PlatformMiningConfig extends PlatformConfigFiles {
  def miningConfigRaw: Config = alephium.getConfig("mining").resolve()

  val nonceStep: BigInt = miningConfigRaw.getInt("nonceStep")
}

trait PlatformNetworkConfig extends PlatformConfigFiles {
  def networkConfigRaw: Config = alephium.getConfig("network").resolve()

  def parseAddress(s: String): InetSocketAddress = {
    val List(left, right) = s.split(':').toList
    new InetSocketAddress(left, right.toInt)
  }

  val pingFrequency: FiniteDuration    = getDuration(networkConfigRaw, "pingFrequency")
  val retryTimeout: FiniteDuration     = getDuration(networkConfigRaw, "retryTimeout")
  val publicAddress: InetSocketAddress = parseAddress(networkConfigRaw.getString("publicAddress"))
  val masterAddress: InetSocketAddress = parseAddress(networkConfigRaw.getString("masterAddress"))

  val isCoordinator: Boolean = publicAddress == masterAddress
}

trait PlatformGenesisConfig extends PlatformConsensusConfig {
  def loadBlockFlow(): AVector[AVector[Block]] = {
    AVector.tabulate(groups, groups) {
      case (from, to) =>
        PlatformConfig.mineGenesis(ChainIndex(from, to)(this))(this)
    }
  }

  lazy val genesisBlocks: AVector[AVector[Block]] = loadBlockFlow()
}

trait PlatformDiscoveryConfig extends PlatformGroupConfig with PlatformNetworkConfig with DC {
  def discoveryConfig: Config = alephium.getConfig("discovery").resolve()

  val peersPerGroup                             = discoveryConfig.getInt("peersPerGroup")
  val scanMaxPerGroup                           = discoveryConfig.getInt("scanMaxPerGroup")
  val scanFrequency                             = getDuration(discoveryConfig, "scanFrequency")
  val scanFastFrequency                         = getDuration(discoveryConfig, "scanFastFrequency")
  val neighborsPerGroup                         = discoveryConfig.getInt("neighborsPerGroup")
  val (discoveryPrivateKey, discoveryPublicKey) = ED25519.generatePriPub()

  lazy val bootstrap: AVector[InetSocketAddress] =
    Network.parseAddresses(alephium.getString("bootstrap"))
}

class PlatformConfig(val env: Env, val rootPath: Path)
    extends PlatformGroupConfig
    with PlatformConsensusConfig
    with PlatformMiningConfig
    with PlatformGenesisConfig
    with PlatformCliqueConfig
    with PlatformBrokerConfig
    with PlatformDiscoveryConfig { self =>

  val disk: Disk = Disk.createUnsafe(rootPath)

  val dbPath = {
    val path = rootPath.resolve("db")
    Disk.createDirUnsafe(path)
    path
  }
  val dbOptions = RocksDBStorage.Settings.default(RocksDBStorage.Compaction.HDD, columns = 1)

  val headerDB: HeaderDB = {
    val dbName = "all-" + nodeId.shortHex
    HeaderDB.openUnsafe(dbPath.resolve(dbName), new Options().setCreateIfMissing(true))
  }
  val trie: MerklePatriciaTrie = {
    val dbName = "trie-" + brokerId.value
    val storage =
      HeaderDB.openUnsafe(dbPath.resolve(dbName), dbOptions)
    MerklePatriciaTrie.create(storage)
  }
}
