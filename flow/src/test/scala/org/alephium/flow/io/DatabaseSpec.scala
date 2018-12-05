package org.alephium.flow.io

import akka.util.ByteString
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

  it should "create entities" in new Fixture {
    val (key0, value0) = generate()
    val (key1, value1) = generate()
    val (key2, value2) = generate()
    db.getOpt(key0).right.value is None
    db.getOpt(key1).right.value is None
    db.getOpt(key2).right.value is None
    db.put(key0, value0).isRight is true
    db.put(key1, value1).isRight is true
    db.put(key2, value2).isRight is true
    db.get(key0).right.value is value0
    db.get(key1).right.value is value1
    db.get(key2).right.value is value2
    db.getOpt(key0).right.value.get is value0
    db.getOpt(key1).right.value.get is value1
    db.getOpt(key2).right.value.get is value2
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
}
