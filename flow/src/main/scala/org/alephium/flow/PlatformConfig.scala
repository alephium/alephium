package org.alephium.flow

import java.time.Duration
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Path

import com.typesafe.config.{Config, ConfigFactory}
import com.typesafe.scalalogging.StrictLogging
import org.alephium.crypto.ED25519
import org.alephium.flow.io.{Disk, HeaderDB}
import org.alephium.flow.trie.MerklePatriciaTrie
import org.alephium.protocol.config.{CliqueConfig, ConsensusConfig, GroupConfig, DiscoveryConfig => DC}
import org.alephium.protocol.model._
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

  def mineGenesis(chainIndex: ChainIndex)(
      implicit config: GroupConfig with ConsensusConfig): Block = {
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

  def getDuration(path: String): FiniteDuration = {
    val duration = alephium.getDuration(path)
    FiniteDuration(duration.toNanos, NANOSECONDS)
  }
}

trait PlatformGroupConfig extends PlatformConfigFiles with GroupConfig {
  val groups: Int = alephium.getInt("groups")
}

trait PlatformCliqueConfig extends PlatformConfigFiles with GroupConfig with CliqueConfig {
  def groupConfigRaw: Config = alephium.getConfig("clique").resolve

  val brokerNum: Int         = groupConfigRaw.getInt("brokerNum")
  val groupNumPerBroker: Int = groupConfigRaw.getInt("groupNumPerBroker")
  val cliqueId: CliqueId = {
    val idRaw = groupConfigRaw.getString("cliqueId")
    CliqueId.fromStringUnsafe(idRaw)
  }
  val brokerId: BrokerId = {
    val myId = groupConfigRaw.getInt("brokerId")
    assert(0 <= myId && myId * groupNumPerBroker < groups)
    BrokerId(myId)(this)
  }
}

trait PlatformConsensusConfig extends PlatformConfigFiles with ConsensusConfig {
  def consensusConfigRaw: Config = alephium.getConfig("consensus").resolve

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

trait PlatformMiningConfig extends PlatformConfigFiles {
  def miningConfigRaw: Config = alephium.getConfig("mining").resolve()

  val nonceStep: BigInt = alephium.getInt("nonceStep")
}

trait PlatformNetworkConfig extends PlatformConfigFiles {
  def networkConfigRaw: Config = alephium.getConfig("network").resolve()

  def parseAddress(s: String): InetSocketAddress = {
    val List(left, right) = s.split(':').toList
    new InetSocketAddress(left, right.toInt)
  }

  val pingFrequency: FiniteDuration    = getDuration("pingFrequency")
  val retryTimeout: FiniteDuration     = getDuration("retryTimeout")
  val publicAddress: InetSocketAddress = parseAddress(networkConfigRaw.getString("publicAddress"))
  val masterAddress: InetSocketAddress = parseAddress(networkConfigRaw.getString("masterAddress"))
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
  val scanFrequency                             = discoveryConfig.getDuration("scanFrequency").toMillis.millis
  val scanFastFrequency                         = discoveryConfig.getDuration("scanFastFrequency").toMillis.millis
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
    with PlatformDiscoveryConfig { self =>
  val isMaster: Boolean = publicAddress == masterAddress

  val disk: Disk = Disk.createUnsafe(rootPath)

  val dbPath = {
    val path = rootPath.resolve("db")
    Disk.createDirUnsafe(path)
    path
  }
  val headerDB: HeaderDB = {
    val dbName = "all-" + brokerId.value
    HeaderDB.openUnsafe(dbPath.resolve(dbName), new Options().setCreateIfMissing(true))
  }
  val trie: MerklePatriciaTrie = {
    val dbName = "trie-" + brokerId.value
    val storage =
      HeaderDB.openUnsafe(dbPath.resolve(dbName), new Options().setCreateIfMissing(true))
    MerklePatriciaTrie.create(storage)
  }
}
