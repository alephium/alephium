package org.alephium.flow

import org.alephium.crypto.{ED25519PrivateKey, ED25519PublicKey}
import org.alephium.flow.io.RocksDBStorage
import org.alephium.flow.storage.TestUtils
import org.alephium.protocol.model.{Block, GroupIndex}
import org.alephium.util.{AVector, AlephiumActorSpec, AlephiumSpec}
import org.scalatest.BeforeAndAfter

import scala.language.reflectiveCalls

trait AlephiumFlowSpec extends AlephiumSpec with BeforeAndAfter {
  import PlatformConfig.{env, rootPath}

  val newPath = rootPath.resolveSibling(rootPath.getFileName + this.getClass.getSimpleName)
  implicit val config = new PlatformConfig(env, newPath) {
    val balances = AVector.tabulate[(ED25519PrivateKey, ED25519PublicKey, BigInt)](groups) { i =>
      val groupIndex              = GroupIndex(i)(this)
      val (privateKey, publicKey) = groupIndex.generateKey()(this)
      (privateKey, publicKey, BigInt(100))
    }

    override lazy val genesisBlocks: AVector[AVector[Block]] = loadBlockFlow(
      balances.map(p => (p._2, p._3)))
  }

  val genesisBalances = config.balances

  after {
    TestUtils.clear(config.disk.blockFolder)
    RocksDBStorage.dESTROY(config.headerDB.storage)
  }
}

class AlephiumFlowActorSpec(name: String) extends AlephiumActorSpec(name) with AlephiumFlowSpec
