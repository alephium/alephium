package org.alephium.flow.io

import akka.util.ByteString
import org.alephium.protocol.config.ConsensusConfigFixture
import org.alephium.protocol.model.ModelGen
import org.alephium.util.{AlephiumSpec, Files}
import org.scalacheck.Arbitrary
import org.scalatest.Assertion
import org.scalatest.EitherValues._

import RocksDBStorage.ColumnFamily

class HeaderDBSpec extends AlephiumSpec {

  trait Fixture {
    val tmpdir = Files.tmpDir
    val dbname = "foo"
    val dbPath = tmpdir.resolve(dbname)

    val dbStorage    = RocksDBStorage.openUnsafe(dbPath, RocksDBStorage.Compaction.HDD)
    val db: HeaderDB = HeaderDB(dbStorage, ColumnFamily.All)

    def generate(): (ByteString, ByteString) = {
      val generator = Arbitrary.arbString.arbitrary
      val key       = ByteString.fromString(generator.sample.get)
      val value     = ByteString.fromString(generator.sample.get)
      (key, value)
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
    val (key, value) = generate()
    db.exists(key).right.value is false
    db.put(key, value).isRight is true
    db.exists(key).right.value is true
    postTest()
  }

  it should "delete entities" in new Fixture {
    val (key, value) = generate()
    db.put(key, value).isRight is true
    db.exists(key).right.value is true
    db.delete(key).isRight is true
    db.exists(key).right.value is false
    postTest()
  }

  it should "work for transactions" in new Fixture with ConsensusConfigFixture {
    forAll(ModelGen.blockGen) { block =>
      val header = block.header
      val hash   = block.hash
      db.putHeader(header).isRight is true
      db.getHeader(hash).right.value is header
      db.getHeaderOpt(hash).right.value.get is header
      db.deleteHeader(hash).isRight is true
      db.getHeader(hash).isLeft is true
      db.getHeaderOpt(hash).right.value is None
    }
    postTest()
  }
}
