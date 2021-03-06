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

import org.alephium.io.RocksDBSource
import org.alephium.protocol.config.ConsensusConfigFixture
import org.alephium.protocol.model.{BlockHeader, NoIndexModelGenerators}
import org.alephium.util.{AlephiumSpec, Files}

class BlockHeaderStorageSpec extends AlephiumSpec with NoIndexModelGenerators {
  import RocksDBSource.ColumnFamily

  trait Fixture extends ConsensusConfigFixture.Default {
    val tmpdir = Files.tmpDir
    val dbname = "foo"
    val dbPath = tmpdir.resolve(dbname)

    val source        = RocksDBSource.openUnsafe(dbPath, RocksDBSource.Compaction.HDD)
    val headerStorage = BlockHeaderRockDBStorage(source, ColumnFamily.All)

    def generate(): BlockHeader = {
      val block = blockGen.sample.get
      block.header
    }

    def postTest(): Assertion = {
      source.dESTROY().isRight is true
    }
  }

  it should "create database" in new Fixture {
    RocksDBSource.open(dbPath, RocksDBSource.Compaction.HDD).isLeft is true
    postTest()
  }

  it should "check existence" in new Fixture {
    val blockHeader = generate()
    headerStorage.exists(blockHeader) isE false
    headerStorage.put(blockHeader).isRight is true
    headerStorage.exists(blockHeader) isE true
    postTest()
  }

  it should "delete entities" in new Fixture {
    val blockHeader = generate()
    headerStorage.put(blockHeader).isRight is true
    headerStorage.exists(blockHeader) isE true
    headerStorage.delete(blockHeader).isRight is true
    headerStorage.exists(blockHeader) isE false
    postTest()
  }

  it should "work for transactions" in new Fixture with ConsensusConfigFixture {
    forAll(blockGen) { block =>
      val header = block.header
      val hash   = block.hash
      headerStorage.put(header).isRight is true
      headerStorage.get(hash) isE header
      headerStorage.getOpt(hash) isE Some(header)
      headerStorage.delete(hash).isRight is true
      headerStorage.get(hash).isLeft is true
      headerStorage.getOpt(hash) isE None
    }
    postTest()
  }
}
