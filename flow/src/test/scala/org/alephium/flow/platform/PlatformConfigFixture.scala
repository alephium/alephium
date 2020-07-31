//package org.alephium.flow.platform
//
//import scala.jdk.CollectionConverters._
//
//import com.typesafe.config.{ConfigFactory, ConfigValueFactory}
//
//import org.alephium.crypto.{ED25519PrivateKey, ED25519PublicKey}
//import org.alephium.protocol.config.{ConsensusConfig, GroupConfig}
//import org.alephium.protocol.model.GroupIndex
//import org.alephium.protocol.vm.LockupScript
//import org.alephium.util.{AVector, Env, U64}
//
//trait PlatformConfigFixture {
//  val configValues: Map[String, Any] = Map.empty
//
//  val genesisBalance: U64 = U64.unsafe(100)
//
//  val env      = Env.resolve()
//  val rootPath = Platform.generateRootPath(env)
//
//  lazy val newConfig = ConfigFactory
//    .parseMap(configValues.view.mapValues(ConfigValueFactory.fromAnyRef).toMap.asJava)
//    .withFallback(Configs.parseConfig(rootPath))
//
//  lazy val groups0 = newConfig.getInt("alephium.groups")
//
//  lazy val groupConfig: GroupConfig = new GroupConfig { override def groups: Int = groups0 }
//
//  lazy val genesisBalances =
//    AVector.tabulate[(ED25519PrivateKey, ED25519PublicKey, U64)](groups0) { i =>
//      val groupIndex              = GroupIndex.unsafe(i)(groupConfig)
//      val (privateKey, publicKey) = groupIndex.generateKey(groupConfig)
//      (privateKey, publicKey, genesisBalance)
//    }
//
//  implicit lazy val config =
//    PlatformConfig.build(newConfig,
//                         rootPath,
//                         Some(genesisBalances.map(p => (LockupScript.p2pkh(p._2), p._3))))
//
//  lazy val consensusConfig: ConsensusConfig = config
//}
