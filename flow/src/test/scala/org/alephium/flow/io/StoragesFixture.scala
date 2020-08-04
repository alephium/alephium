package org.alephium.flow.io

import java.nio.file.{Files, Path}

import org.alephium.io.RocksDBSource
import org.alephium.protocol.Hash
import org.alephium.protocol.config.GroupConfig

trait StoragesFixture {
  def rootPath: Path
  implicit def groupConfig: GroupConfig

  lazy val storages = StoragesFixture.buildStorages(rootPath)

  def cleanStorages(): Unit = {
    storages.dESTROYUnsafe()
  }
}

object StoragesFixture {
  def buildStorages(rootPath: Path)(implicit groupConfig: GroupConfig): Storages = {
    if (!Files.exists(rootPath)) rootPath.toFile.mkdir()

    val postFix   = Hash.random.toHexString
    val dbFolders = s"db-$postFix"
    val storages: Storages =
      Storages.createUnsafe(rootPath, dbFolders, RocksDBSource.Settings.syncWrite)
    storages
  }
}
