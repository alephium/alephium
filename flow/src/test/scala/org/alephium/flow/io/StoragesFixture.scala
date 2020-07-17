package org.alephium.flow.io

import org.alephium.flow.platform.PlatformConfig
import org.alephium.io.RocksDBSource
import org.alephium.protocol.ALF.Hash

trait StoragesFixture {
  implicit def config: PlatformConfig
  lazy val storages = StoragesFixture.buildStorages

  def cleanStorages(): Unit = {
    storages.dESTROYUnsafe()
  }
}

object StoragesFixture {
  def buildStorages(implicit config: PlatformConfig): Storages = {
    val postFix   = Hash.random.toHexString
    val dbFolders = s"db-$postFix"
    val storages: Storages =
      Storages.createUnsafe(config.rootPath, dbFolders, RocksDBSource.Settings.syncWrite)
    storages
  }
}
