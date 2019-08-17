package org.alephium.flow.storage

import org.alephium.crypto.{ED25519PrivateKey, ED25519PublicKey}
import org.alephium.flow.PlatformConfig
import org.alephium.flow.io.RocksDBStorage
import org.alephium.protocol.model.{Block, GroupIndex}
import org.alephium.util.{AVector, AlephiumSpec}
import org.scalatest.BeforeAndAfter

trait BlockFlowFixture extends AlephiumSpec with BeforeAndAfter {
  import PlatformConfig.{env, rootPath}

  implicit val config: PlatformConfig = new PlatformConfig(env, rootPath) {
    val addresses: AVector[(ED25519PrivateKey, ED25519PublicKey)] =
      AVector.tabulate(groups) { i =>
        val groupIndex = GroupIndex(i)(this)
        groupIndex.generateKey()(this)
      }

    val balances = addresses.map(key => (key._2, BigInt(100)))

    override lazy val genesisBlocks: AVector[AVector[Block]] = loadBlockFlow(balances)
  }

  after {
    TestUtils.clear(config.disk.blockFolder)
    RocksDBStorage.dESTROY(config.headerDB.storage)
  }
}
