package org.alephium.flow.io

import org.scalatest.Assertion
import org.scalatest.EitherValues._

import org.alephium.protocol.config.ConsensusConfigFixture
import org.alephium.protocol.model.{BlockHeader, ModelGen}
import org.alephium.util.{AlephiumSpec, Files}

class BlockHeaderStorageSpec extends AlephiumSpec {
  import RocksDBSource.ColumnFamily

  trait Fixture extends ConsensusConfigFixture {
    val tmpdir = Files.tmpDir
    val dbname = "foo"
    val dbPath = tmpdir.resolve(dbname)

    val dbStorage              = RocksDBSource.openUnsafe(dbPath, RocksDBSource.Compaction.HDD)
    val db: BlockHeaderStorage = BlockHeaderRockDBStorage(dbStorage, ColumnFamily.All)

    def generate(): BlockHeader = {
      val block = ModelGen.blockGen.sample.get
      block.header
    }

    def postTest(): Assertion = {
      dbStorage.close()
      RocksDBSource.dESTROY(dbPath).isRight is true
    }
  }

  it should "create database" in new Fixture {
    RocksDBSource.open(dbPath, RocksDBSource.Compaction.HDD).isLeft is true
    postTest()
  }

  it should "check existence" in new Fixture {
    val blockHeader = generate()
    db.exists(blockHeader) isE false
    db.put(blockHeader).isRight is true
    db.exists(blockHeader) isE true
    postTest()
  }

  it should "delete entities" in new Fixture {
    val blockHeader = generate()
    db.put(blockHeader).isRight is true
    db.exists(blockHeader) isE true
    db.delete(blockHeader).isRight is true
    db.exists(blockHeader) isE false
    postTest()
  }

  it should "work for transactions" in new Fixture with ConsensusConfigFixture {
    forAll(ModelGen.blockGen) { block =>
      val header = block.header
      val hash   = block.hash
      db.put(header).isRight is true
      db.get(hash) isE header
      db.getOpt(hash).right.value.get is header
      db.delete(hash).isRight is true
      db.get(hash).isLeft is true
      db.getOpt(hash) isE None
    }
    postTest()
  }
}
