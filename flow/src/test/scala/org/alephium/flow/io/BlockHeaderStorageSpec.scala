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

import org.alephium.io.{IOError, RocksDBSource}
import org.alephium.protocol.config.ConsensusConfigFixture
import org.alephium.protocol.model.{BlockHeader, NoIndexModelGenerators}
import org.alephium.util.AlephiumSpec

class BlockHeaderStorageSpec
    extends AlephiumSpec
    with NoIndexModelGenerators
    with StorageSpec[BlockHeaderRockDBStorage] {
  import RocksDBSource.ColumnFamily

  override val dbname: String = "block-header-storage-spec"
  override val builder: RocksDBSource => BlockHeaderRockDBStorage =
    source => BlockHeaderRockDBStorage(source, ColumnFamily.All)

  def generate(): BlockHeader = {
    val block = blockGen.sample.get
    block.header
  }

  it should "create database" in {
    RocksDBSource.open(dbPath, RocksDBSource.Compaction.HDD).isLeft is true
  }

  it should "check existence" in {
    val blockHeader = generate()
    storage.exists(blockHeader) isE false
    storage.put(blockHeader) isE ()
    storage.exists(blockHeader) isE true
  }

  it should "delete entities" in {
    val blockHeader = generate()
    storage.put(blockHeader) isE ()
    storage.exists(blockHeader) isE true
    storage.delete(blockHeader) isE ()
    storage.exists(blockHeader) isE false
  }

  it should "work for transactions" in new ConsensusConfigFixture.Default {
    forAll(blockGen) { block =>
      val header = block.header
      val hash   = block.hash
      storage.put(header) isE ()
      storage.get(hash) isE header
      storage.getOpt(hash) isE Some(header)
      storage.remove(hash) isE ()
      storage.get(hash).leftValue is a[IOError.KeyNotFound]
      storage.getOpt(hash) isE None
    }
  }
}
