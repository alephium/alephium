package org.alephium.flow.platform

import java.net.InetSocketAddress
import java.nio.file.Path
import java.time.Duration

import scala.annotation.tailrec
import scala.concurrent.duration._

import com.typesafe.config.Config
import org.rocksdb.WriteOptions

import org.alephium.crypto.{ED25519, ED25519PublicKey}
import org.alephium.flow.io.RocksDBStorage.Settings
import org.alephium.protocol.config.ConsensusConfig
import org.alephium.protocol.model._
import org.alephium.util.{AVector, Env, Hex, Network}

trait PlatformProfile
    extends NewConfig.PlatformGroupConfig
    with NewConfig.PlatformCliqueConfig
    with NewConfig.PlatformConsensusConfig
    with NewConfig.PlatformDiscoveryConfig
    with NewConfig.PlatformBrokerConfig
    with NewConfig.PlatformGenesisConfig
    with NewConfig.PlatformMiningConfig
    with NewConfig.PlatformNetworkConfig
    with PlatformIO {
  def all: Config
  def aleph: Config
}

object PlatformProfile {
  import NewConfig._

  def loadDefault(): PlatformProfile = {
    PlatformProfile.load(Platform.getRootPath(Env.resolve()))
  }

  def load(rootPath: Path,
           rdbWriteOptions: WriteOptions                                = Settings.writeOptions,
           genesisBalances: Option[AVector[(ED25519PublicKey, BigInt)]] = None): PlatformProfile = {
    val allCfg   = parseConfig(rootPath)
    val alephCfg = allCfg.getConfig("alephium")
    create(
      rootPath,
      allCfg,
      alephCfg,
      alephCfg.getConfig("clique"),
      alephCfg.getConfig("broker"),
      alephCfg.getConfig("consensus"),
      alephCfg.getConfig("mining"),
      alephCfg.getConfig("network"),
      alephCfg.getConfig("discovery"),
      rdbWriteOptions,
      genesisBalances
    )
  }

  // scalastyle:off method.length parameter.number
  def create(rootPath0: Path,
             allCfg: Config,
             alephCfg: Config,
             cliqueCfg: Config,
             brokerCfg: Config,
             consensusCfg: Config,
             miningCfg: Config,
             networkCfg: Config,
             discoveryCfg: Config,
             rdbWriteOptions: WriteOptions,
             genesisBalances: Option[AVector[(ED25519PublicKey, BigInt)]]): PlatformProfile =
    new PlatformProfile {
      /* Common */
      final val all      = allCfg
      final val aleph    = alephCfg
      final val rootPath = rootPath0
      /* Common */

      /* Group */
      final val groups: Int = alephCfg.getInt("groups")
      /* Group */

      /* Clique */
      final val brokerNum: Int         = cliqueCfg.getInt("brokerNum")
      final val groupNumPerBroker: Int = groups / brokerNum
      require(groups % brokerNum == 0)
      /* Clique */

      /* Consensus */
      final val numZerosAtLeastInHash: Int = consensusCfg.getInt("numZerosAtLeastInHash")
      final val maxMiningTarget: BigInt    = (BigInt(1) << (256 - numZerosAtLeastInHash)) - 1

      final val blockTargetTime: Duration = consensusCfg.getDuration("blockTargetTime")
      final val blockConfirmNum: Int      = consensusCfg.getInt("blockConfirmNum")
      final val expectedTimeSpan: Long    = blockTargetTime.toMillis

      final val blockCacheSize
        : Int = consensusCfg.getInt("blockCacheSizePerChain") * (2 * groups - 1)

      final val medianTimeInterval = 11
      final val diffAdjustDownMax  = 16
      final val diffAdjustUpMax    = 8
      final val timeSpanMin: Long  = expectedTimeSpan * (100 - diffAdjustDownMax) / 100
      final val timeSpanMax: Long  = expectedTimeSpan * (100 + diffAdjustUpMax) / 100
      /* Consensus */

      /* mining */
      final val nonceStep: BigInt = miningCfg.getInt("nonceStep")
      /* mining */

      /* Network */
      final val pingFrequency: FiniteDuration = getDuration(networkCfg, "pingFrequency")
      final val retryTimeout: FiniteDuration  = getDuration(networkCfg, "retryTimeout")
      final val publicAddress: InetSocketAddress = parseAddress(
        networkCfg.getString("publicAddress"))
      final val masterAddress: InetSocketAddress = parseAddress(
        networkCfg.getString("masterAddress"))
      final val numOfSyncBlocksLimit: Int = networkCfg.getInt("numOfSyncBlocksLimit")
      final val isCoordinator: Boolean    = publicAddress == masterAddress
      /* Network */

      /* Broker */
      final val brokerInfo: BrokerInfo = {
        val myId = brokerCfg.getInt("brokerId")
        BrokerInfo(myId, groupNumPerBroker, publicAddress)(this)
      }
      /* Broker */

      /* Discovery */
      final val peersPerGroup                             = discoveryCfg.getInt("peersPerGroup")
      final val scanMaxPerGroup                           = discoveryCfg.getInt("scanMaxPerGroup")
      final val scanFrequency                             = getDuration(discoveryCfg, "scanFrequency")
      final val scanFastFrequency                         = getDuration(discoveryCfg, "scanFastFrequency")
      final val neighborsPerGroup                         = discoveryCfg.getInt("neighborsPerGroup")
      final val (discoveryPrivateKey, discoveryPublicKey) = ED25519.generatePriPub()
      final val bootstrap: AVector[InetSocketAddress] =
        Network.parseAddresses(alephCfg.getString("bootstrap"))
      /* Discovery */

      /* Genesis */
      final val genesisBlocks = genesisBalances match {
        case None           => loadBlockFlow(alephCfg)(this)
        case Some(balances) => loadBlockFlow(balances)(this)
      }
      /* Genesis */

      /* IO */
      final val (disk, headerDB, emptyTrie) = {
        val dbFolder = "db"
        val dbName   = s"${brokerInfo.id}-${publicAddress.getPort}"
        PlatformIO.init(rootPath, dbFolder, dbName, rdbWriteOptions)
      }
      /* IO */
    }
  // scalastyle:off method.length parameter.number

  private def splitBalance(raw: String): (ED25519PublicKey, BigInt) = {
    val List(left, right) = raw.split(":").toList
    val publicKey         = ED25519PublicKey.from(Hex.unsafeFrom(left))
    val balance           = BigInt(right)
    (publicKey, balance)
  }

  def loadBlockFlow(cfg: Config)(implicit config: ConsensusConfig): AVector[AVector[Block]] = {
    import collection.JavaConverters._
    val entries  = cfg.getStringList("genesis").asScala
    val balances = entries.map(splitBalance)
    loadBlockFlow(AVector.from(balances))
  }

  def loadBlockFlow(balances: AVector[(ED25519PublicKey, BigInt)])(
      implicit config: ConsensusConfig): AVector[AVector[Block]] = {
    AVector.tabulate(config.groups, config.groups) {
      case (from, to) =>
        val transactions = if (from == to) {
          val balancesOI  = balances.filter(p => GroupIndex.from(p._1).value == from)
          val transaction = Transaction.genesis(balancesOI)
          AVector(transaction)
        } else AVector.empty[Transaction]
        mineGenesis(ChainIndex(from, to)(config), transactions)
    }
  }

  def mineGenesis(chainIndex: ChainIndex, transactions: AVector[Transaction])(
      implicit config: ConsensusConfig): Block = {
    @tailrec
    def iter(nonce: BigInt): Block = {
      val block = Block.genesis(transactions, config.maxMiningTarget, nonce)
      // Note: we do not validate difficulty target here
      if (block.validateIndex(chainIndex)) block else iter(nonce + 1)
    }

    iter(0)
  }
}
