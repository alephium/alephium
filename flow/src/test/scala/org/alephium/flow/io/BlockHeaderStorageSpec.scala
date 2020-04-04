package org.alephium.flow.io

import org.scalatest.Assertion
import org.scalatest.EitherValues._

import org.alephium.protocol.config.ConsensusConfigFixture
import org.alephium.protocol.model.{BlockHeader, ModelGen}
import org.alephium.util.{AlephiumSpec, Files}

class BlockHeaderStorageSpec extends AlephiumSpec {
  import RocksDBStorage.ColumnFamily

  trait Fixture extends ConsensusConfigFixture {
    val tmpdir = Files.tmpDir
    val dbname = "foo"
    val dbPath = tmpdir.resolve(dbname)

    val dbStorage              = RocksDBStorage.openUnsafe(dbPath, RocksDBStorage.Compaction.HDD)
    val db: BlockHeaderStorage = BlockHeaderStorage(dbStorage, ColumnFamily.All)

    def generate(): BlockHeader = {
      val block = ModelGen.blockGen.sample.get
      block.header
    }

    def postTest(): Assertion = {
      dbStorage.close()
      RocksDBStorage.dESTROY(dbPath).isRight is true
    }
  }

  it should "create database" in new Fixture {
    RocksDBStorage.open(dbPath, RocksDBStorage.Compaction.HDD).isLeft is true
    postTest()
  }

  it should "check existence" in new Fixture {
    val blockHeader = generate()
    db.exists(blockHeader).right.value is false
    db.put(blockHeader).isRight is true
    db.exists(blockHeader).right.value is true
    postTest()
  }

  it should "delete entities" in new Fixture {
    val blockHeader = generate()
    db.put(blockHeader).isRight is true
    db.exists(blockHeader).right.value is true
    db.delete(blockHeader).isRight is true
    db.exists(blockHeader).right.value is false
    postTest()
  }

  it should "work for transactions" in new Fixture with ConsensusConfigFixture {
    forAll(ModelGen.blockGen) { block =>
      val header = block.header
      val hash   = block.hash
      db.put(header).isRight is true
      db.get(hash).right.value is header
      db.getOpt(hash).right.value.get is header
      db.delete(hash).isRight is true
      db.get(hash).isLeft is true
      db.getOpt(hash).right.value is None
    }
    postTest()
  }
}
