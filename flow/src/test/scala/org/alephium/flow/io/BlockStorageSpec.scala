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

import org.scalatest.Assertion

import org.alephium.io._
import org.alephium.protocol.config.ConsensusConfigFixture
import org.alephium.protocol.model.NoIndexModelGenerators
import org.alephium.util.{AlephiumSpec, Files}

class BlockStorageSpec extends AlephiumSpec with NoIndexModelGenerators {
  import RocksDBSource.ColumnFamily

  trait Fixture extends ConsensusConfigFixture {
    val tmpdir = Files.tmpDir
    val dbname = "block-storage-spec"
    val dbPath = tmpdir.resolve(dbname)

    val source  = RocksDBSource.openUnsafe(dbPath, RocksDBSource.Compaction.HDD)
    val storage = BlockRockDBStorage(source, ColumnFamily.All)

    def postTest(): Assertion = {
      source.dESTROY().isRight is true
    }
  }

  it should "save and read blocks" in new Fixture with ConsensusConfigFixture {
    forAll(blockGen) { block =>
      storage.exists(block.hash) isE false
      storage.existsUnsafe(block.hash) is false
      storage.put(block).isRight is true
      storage.existsUnsafe(block.hash) is true
      storage.exists(block.hash) isE true
      storage.getUnsafe(block.hash) is block
      storage.get(block.hash) isE block
    }
    postTest()
  }

  it should "fail to delete" in new Fixture with ConsensusConfigFixture {
    forAll(blockGen) { block =>
      storage.put(block).isRight is true
      assertThrows[NotImplementedError] {
        storage.delete(block.hash)
      }
      assertThrows[NotImplementedError] {
        storage.deleteUnsafe(block.hash)
      }
    }
    postTest()
  }
}
