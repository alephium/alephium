package org.alephium.flow.io

import org.alephium.flow.core.TestUtils
import org.alephium.flow.platform.PlatformConfig
import org.alephium.protocol.ALF.Hash

trait StoragesFixture {
  implicit def config: PlatformConfig
  lazy val storages = StoragesFixture.buildStorages

  def cleanStorages(): Unit = {
    TestUtils.clear(storages.blockStorage.folder)
    storages.closeUnsafe()
    RocksDBSource.dESTROYUnsafe(storages.rocksDBSource)
  }
}

object StoragesFixture {
  def buildStorages(implicit config: PlatformConfig): Storages = {
    val postFix      = Hash.random.toHexString
    val dbFolders    = s"db-$postFix"
    val blocksFolder = s"blocks-$postFix"
    val storages: Storages =
      Storages.createUnsafe(config.rootPath,
                            dbFolders,
                            blocksFolder,
                            RocksDBSource.Settings.syncWrite)
    storages
  }
}
