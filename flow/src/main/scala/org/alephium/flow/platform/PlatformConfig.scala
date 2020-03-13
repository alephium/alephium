package org.alephium.flow.platform

import java.net.InetSocketAddress
import java.nio.file.Path

import scala.annotation.tailrec

import com.typesafe.config.Config
import org.rocksdb.WriteOptions

import org.alephium.crypto.{ED25519, ED25519PublicKey}
import org.alephium.flow.io.RocksDBStorage.Settings
import org.alephium.protocol.config.ConsensusConfig
import org.alephium.protocol.model._
import org.alephium.util._

trait PlatformConfig
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

  def txPoolCapacity: Int
  def txMaxNumberPerBlock: Int
}

@SuppressWarnings(Array("org.wartremover.warts.OptionPartial"))
object PlatformConfig {
  import NewConfig._

  def loadDefault(): PlatformConfig = {
    PlatformConfig.load(Platform.getRootPath(Env.resolve()))
  }

  def load(rootPath: Path): PlatformConfig = {
    load(rootPath, Settings.writeOptions, None)
  }

  def load(rootPath: Path,
           rdbWriteOptions: WriteOptions,
           genesisBalances: Option[AVector[(ED25519PublicKey, BigInt)]]): PlatformConfig =
    build(parseConfig(rootPath), rootPath, rdbWriteOptions, genesisBalances)

  def build(config: Config,
            rootPath: Path,
            rdbWriteOptions: WriteOptions,
            genesisBalances: Option[AVector[(ED25519PublicKey, BigInt)]]): PlatformConfig = {
    val alephCfg = config.getConfig("alephium")
    create(
      rootPath,
      config,
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
             genesisBalances: Option[AVector[(ED25519PublicKey, BigInt)]]): PlatformConfig =
    new PlatformConfig {
      /* Common */
      val all      = allCfg
      val aleph    = alephCfg
      val rootPath = rootPath0
      /* Common */

      /* Group */
      val groups: Int = alephCfg.getInt("groups")
      /* Group */

      /* Clique */
      val brokerNum: Int         = cliqueCfg.getInt("brokerNum")
      val groupNumPerBroker: Int = groups / brokerNum
      require(groups % brokerNum == 0)
      /* Clique */

      /* Consensus */
      val numZerosAtLeastInHash: Int = consensusCfg.getInt("numZerosAtLeastInHash")
      val maxMiningTarget: BigInt    = (BigInt(1) << (256 - numZerosAtLeastInHash)) - 1

      val blockTargetTime: Duration  = Duration.from(consensusCfg.getDuration("blockTargetTime")).get
      val blockConfirmNum: Int       = consensusCfg.getInt("blockConfirmNum")
      val expectedTimeSpan: Duration = blockTargetTime

      final val blockCacheSize
        : Int = consensusCfg.getInt("blockCacheSizePerChain") * (2 * groups - 1)

      val medianTimeInterval    = 11
      val diffAdjustDownMax     = 16
      val diffAdjustUpMax       = 8
      val timeSpanMin: Duration = (expectedTimeSpan * (100l - diffAdjustDownMax)).get divUnsafe 100l
      val timeSpanMax: Duration = (expectedTimeSpan * (100l + diffAdjustUpMax)).get divUnsafe 100l
      /* Consensus */

      /* mining */
      val nonceStep: BigInt = miningCfg.getInt("nonceStep")
      /* mining */

      /* Network */
      val pingFrequency: Duration          = getDuration(networkCfg, "pingFrequency")
      val retryTimeout: Duration           = getDuration(networkCfg, "retryTimeout")
      val publicAddress: InetSocketAddress = parseAddress(networkCfg.getString("publicAddress"))
      val masterAddress: InetSocketAddress = parseAddress(networkCfg.getString("masterAddress"))
      val numOfSyncBlocksLimit: Int        = networkCfg.getInt("numOfSyncBlocksLimit")
      val isCoordinator: Boolean           = publicAddress == masterAddress
      /* Network */

      /* Broker */
      val brokerInfo: BrokerInfo = {
        val myId = brokerCfg.getInt("brokerId")
        BrokerInfo(myId, groupNumPerBroker, publicAddress)(this)
      }
      /* Broker */

      /* Discovery */
      val peersPerGroup: Int                        = discoveryCfg.getInt("peersPerGroup")
      val scanMaxPerGroup: Int                      = discoveryCfg.getInt("scanMaxPerGroup")
      val scanFrequency: Duration                   = getDuration(discoveryCfg, "scanFrequency")
      val scanFastFrequency: Duration               = getDuration(discoveryCfg, "scanFastFrequency")
      val neighborsPerGroup: Int                    = discoveryCfg.getInt("neighborsPerGroup")
      val (discoveryPrivateKey, discoveryPublicKey) = ED25519.generatePriPub()
      val bootstrap: AVector[InetSocketAddress] =
        Network.parseAddresses(alephCfg.getString("bootstrap"))
      /* Discovery */

      /* Genesis */
      val genesisBlocks: AVector[AVector[Block]] = genesisBalances match {
        case None           => loadBlockFlow(alephCfg)(this)
        case Some(balances) => loadBlockFlow(balances)(this)
      }
      /* Genesis */

      /* IO */
      val (disk, headerDB, emptyTrie) = {
        val dbFolder = "db"
        val dbName   = s"${brokerInfo.id}-${publicAddress.getPort}"
        PlatformIO.init(rootPath, dbFolder, dbName, rdbWriteOptions)
      }
      /* IO */

      /* Platform */
      val txPoolCapacity      = 1000
      val txMaxNumberPerBlock = 1000
      /* Platform */
    }
  // scalastyle:off method.length parameter.number

  private def splitBalance(raw: String): (ED25519PublicKey, BigInt) = {
    val List(left, right) = raw.split(":").toList
    val publicKeyOpt      = Hex.from(left).flatMap(ED25519PublicKey.from)
    require(publicKeyOpt.nonEmpty, "Invalid public key")

    val publicKey = publicKeyOpt.get
    val balance   = BigInt(right)
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
          val balancesOI  = balances.filter(p => GroupIndex.fromP2PKH(p._1).value == from)
          val transaction = Transaction.genesis(balancesOI)
          AVector(transaction)
        } else AVector.empty[Transaction]
        mineGenesis(ChainIndex.from(from, to).get, transactions)
    }
  }

  def mineGenesis(chainIndex: ChainIndex, transactions: AVector[Transaction])(
      implicit config: ConsensusConfig): Block = {
    @tailrec
    def iter(nonce: BigInt): Block = {
      val block = Block.genesis(transactions, config.maxMiningTarget, nonce)
      // Note: we do not validate difficulty target here
      if (block.chainIndex == chainIndex) block else iter(nonce + 1)
    }

    iter(0)
  }
}
