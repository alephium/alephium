package org.alephium.flow.io

import org.scalatest.Assertion

import org.alephium.protocol.config.ConsensusConfigFixture
import org.alephium.protocol.model.{BlockHeader, ModelGen}
import org.alephium.util.{AlephiumSpec, Files}

class BlockHeaderStorageSpec extends AlephiumSpec {
  import RocksDBSource.ColumnFamily

  trait Fixture extends ConsensusConfigFixture {
    val tmpdir = Files.tmpDir
    val dbname = "foo"
    val dbPath = tmpdir.resolve(dbname)

    val source        = RocksDBSource.openUnsafe(dbPath, RocksDBSource.Compaction.HDD)
    val headerStorage = BlockHeaderRockDBStorage(source, ColumnFamily.All)

    def generate(): BlockHeader = {
      val block = ModelGen.blockGen.sample.get
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
    forAll(ModelGen.blockGen) { block =>
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
