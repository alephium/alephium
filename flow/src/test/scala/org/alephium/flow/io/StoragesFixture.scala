// Copyright 2018 The Alephium Authors
// This file is part of the alephium project.
//
// The library is free software: you can redistribute it and/or modify
// it under the terms of the GNU Lesser General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// The library is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
// GNU Lesser General Public License for more details.
//
// You should have received a copy of the GNU Lesser General Public License
// along with the library. If not, see <http://www.gnu.org/licenses/>.

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
