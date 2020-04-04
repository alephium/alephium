package org.alephium.flow.platform

import scala.collection.JavaConverters._

import com.typesafe.config.{ConfigFactory, ConfigValueFactory}

import org.alephium.crypto.{ED25519PrivateKey, ED25519PublicKey}
import org.alephium.flow.io.RocksDBSource.Settings
import org.alephium.protocol.ALF.Hash
import org.alephium.protocol.config.GroupConfig
import org.alephium.protocol.model.GroupIndex
import org.alephium.protocol.script.PayTo
import org.alephium.util.{AVector, Env}

trait PlatformConfigFixture {
  val configValues: Map[String, Any] = Map.empty

  val genesisBalance: BigInt = 100

  val env      = Env.resolve()
  val rootPath = Platform.getRootPath(env)

  val newPath = rootPath.resolveSibling(rootPath.getFileName + "-" + Hash.random.toHexString)

  lazy val newConfig =
    ConfigFactory
      .parseMap(configValues.mapValues(ConfigValueFactory.fromAnyRef).asJava)
      .withFallback(Configs.parseConfig(newPath))

  lazy val groups0 = newConfig.getInt("alephium.groups")

  lazy val groupConfig = new GroupConfig { override def groups: Int = groups0 }

  lazy val genesisBalances =
    AVector.tabulate[(ED25519PrivateKey, ED25519PublicKey, BigInt)](groups0) { i =>
      val groupIndex              = GroupIndex.unsafe(i)(groupConfig)
      val (privateKey, publicKey) = groupIndex.generateKey(PayTo.PKH)(groupConfig)
      (privateKey, publicKey, genesisBalance)
    }

  implicit lazy val config =
    PlatformConfig.build(newConfig,
                         newPath,
                         Settings.syncWrite,
                         Some(genesisBalances.map(p => (p._2, p._3))))
}
