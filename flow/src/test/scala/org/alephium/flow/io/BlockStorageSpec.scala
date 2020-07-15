package org.alephium.flow.io

import org.scalatest.Assertion

import org.alephium.io._
import org.alephium.protocol.config.ConsensusConfigFixture
import org.alephium.protocol.model.ModelGenerators
import org.alephium.util.{AlephiumSpec, Files}

class BlockStorageSpec extends AlephiumSpec with ModelGenerators {
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
