package org.alephium.flow.platform

import java.net.InetSocketAddress
import java.nio.file.Path

import scala.annotation.tailrec

import com.typesafe.config.Config

import org.alephium.crypto.{ED25519, ED25519PublicKey}
import org.alephium.protocol.config.ConsensusConfig
import org.alephium.protocol.model._
import org.alephium.protocol.script.PayTo
import org.alephium.util._

trait PlatformConfig extends Configs with PlatformIO {
  def all: Config
  def aleph: Config

  def txPoolCapacity: Int
  def txMaxNumberPerBlock: Int
}

@SuppressWarnings(Array("org.wartremover.warts.OptionPartial"))
object PlatformConfig {
  import Configs._

  def loadDefault(): PlatformConfig = {
    PlatformConfig.load(Platform.generateRootPath(Env.resolve()))
  }

  def load(rootPath: Path): PlatformConfig = {
    load(rootPath, None)
  }

  def load(rootPath: Path,
           genesisBalances: Option[AVector[(ED25519PublicKey, U64)]]): PlatformConfig =
    build(parseConfig(rootPath), rootPath, genesisBalances)

  def build(config: Config,
            rootPath: Path,
            genesisBalances: Option[AVector[(ED25519PublicKey, U64)]]): PlatformConfig = {
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
             genesisBalances: Option[AVector[(ED25519PublicKey, U64)]]): PlatformConfig =
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
      val tipsPruneInterval: Int     = consensusCfg.getInt("tipsPruneInterval")
      val expectedTimeSpan: Duration = blockTargetTime

      val medianTimeInterval    = 11
      val diffAdjustDownMax     = 16
      val diffAdjustUpMax       = 8
      val timeSpanMin: Duration = (expectedTimeSpan * (100L - diffAdjustDownMax)).get divUnsafe 100L
      val timeSpanMax: Duration = (expectedTimeSpan * (100L + diffAdjustUpMax)).get divUnsafe 100L
      /* Consensus */

      /* Script */
      val maxStackSize: Int = 1024
      /* Script */

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

      val rpcPort: Option[Int] = extractPort(networkCfg.getInt("rpcPort"))
      val wsPort: Option[Int]  = extractPort(networkCfg.getInt("wsPort"))
      /* Network */

      /* Broker */
      val brokerInfo: BrokerInfo = {
        val myId = brokerCfg.getInt("brokerId")
        BrokerInfo.from(myId, groupNumPerBroker, publicAddress)(this).get
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
      val blockCacheCapacityPerChain = consensusCfg.getInt("blockCacheCapacityPerChain")
      val blockCacheCapacity: Int    = blockCacheCapacityPerChain * depsNum
      /* IO */

      /* Platform */
      val txPoolCapacity      = 1000
      val txMaxNumberPerBlock = 1000
      /* Platform */
    }
  // scalastyle:off method.length parameter.number

  private def splitBalance(raw: String): (ED25519PublicKey, U64) = {
    val List(left, right) = raw.split(":").toList
    val publicKeyOpt      = Hex.from(left).flatMap(ED25519PublicKey.from)
    require(publicKeyOpt.nonEmpty, "Invalid public key")

    val publicKey = publicKeyOpt.get
    val balance = {
      val parsed = BigInt(right)
      require(parsed >= 0 && parsed <= Long.MaxValue)
      U64.unsafe(parsed.longValue)
    }
    (publicKey, balance)
  }

  def loadBlockFlow(cfg: Config)(implicit config: ConsensusConfig): AVector[AVector[Block]] = {
    import scala.jdk.CollectionConverters._
    val entries  = cfg.getStringList("genesis").asScala
    val balances = entries.map(splitBalance)
    loadBlockFlow(AVector.from(balances))
  }

  def loadBlockFlow(balances: AVector[(ED25519PublicKey, U64)])(
      implicit config: ConsensusConfig): AVector[AVector[Block]] = {
    AVector.tabulate(config.groups, config.groups) {
      case (from, to) =>
        val transactions = if (from == to) {
          val balancesOI  = balances.filter(p => GroupIndex.from(PayTo.PKH, p._1).value == from)
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
