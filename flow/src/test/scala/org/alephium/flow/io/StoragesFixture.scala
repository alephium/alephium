package org.alephium.flow.io

import org.alephium.flow.core.TestUtils
import org.alephium.flow.platform.PlatformConfig
import org.alephium.protocol.ALF.Hash

trait StoragesFixture {
  def config: PlatformConfig
  lazy val (db, storages) = StoragesFixture.buildStorages(config)

  def cleanStorages() = {
    TestUtils.clear(storages.blockStorage.folder)
    RocksDBSource.dESTROY(db)
  }
}
object StoragesFixture {
  def buildStorages(implicit config: PlatformConfig): (RocksDBSource, Storages) = {
    val db: RocksDBSource =
      RocksDBSource.createUnsafe(config.rootPath, "db", Hash.random.toHexString)
    val storages: Storages =
      Storages.createUnsafe(config.rootPath, db, RocksDBSource.Settings.syncWrite)(config)
    (db, storages)
  }
}
