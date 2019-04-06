package org.alephium.flow.io

import akka.util.ByteString
import org.alephium.protocol.config.ConsensusConfigFixture
import org.alephium.protocol.model.{ModelGen, TxOutputPoint}
import org.alephium.util.{AlephiumSpec, Files}
import org.rocksdb.Options
import org.scalacheck.Arbitrary
import org.scalatest.Assertion
import org.scalatest.EitherValues._

class DatabaseSpec extends AlephiumSpec {

  trait Fixture {
    val tmpdir = Files.tmpDir
    val dbname = "foo"
    val dbPath = tmpdir.resolve(dbname)

    val db = Database.open(dbPath, new Options().setCreateIfMissing(true)).right.value

    def generate(): (ByteString, ByteString) = {
      val generator = Arbitrary.arbString.arbitrary
      val key       = ByteString.fromString(generator.sample.get)
      val value     = ByteString.fromString(generator.sample.get)
      (key, value)
    }

    def postTest(): Assertion = {
      db.close()
      Database.dESTROY(dbPath, new Options()).isRight is true
    }
  }

  it should "create database" in new Fixture {
    Database.open(dbPath, new Options().setErrorIfExists(true)).isLeft is true
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

  it should "work for utxo" in new Fixture with ConsensusConfigFixture {
    forAll(ModelGen.transactionGen) { transaction =>
      val hash = transaction.hash
      transaction.unsigned.outputs.foreachWithIndex { (output, index) =>
        val outputPoint = TxOutputPoint(hash, index)
        db.putUTXO(outputPoint, output)
        db.getUTXO(outputPoint).right.value is output
        db.getUTXOOpt(outputPoint).right.value.get is output
        db.deleteUTXO(outputPoint).isRight is true
        db.getUTXO(outputPoint).isLeft is true
        db.getUTXOOpt(outputPoint).right.value is None
      }
    }
    postTest()
  }
}
