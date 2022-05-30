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

package org.alephium.io

import scala.annotation.tailrec

import akka.util.ByteString
import org.scalacheck.{Arbitrary, Gen}
import org.scalatest.Assertion

import org.alephium.crypto.{Blake2b => Hash}
import org.alephium.serde._
import org.alephium.util.{AlephiumSpec, AVector}

class SparseMerkleTrieSpec extends AlephiumSpec {
  import SparseMerkleTrie._

  behavior of "nibbles calculation"

  it should "calculate correct nibbles" in {
    def test(input: Byte, high: Byte, low: Byte): Assertion = {
      SparseMerkleTrie.getLowNibble(input) is low
      SparseMerkleTrie.getHighNibble(input) is high
    }
    test(0x00.toByte, 0x00.toByte, 0x00.toByte)
    test(0x0f.toByte, 0x00.toByte, 0x0f.toByte)
    test(0xf0.toByte, 0x0f.toByte, 0x00.toByte)
    test(0xff.toByte, 0x0f.toByte, 0x0f.toByte)
  }

  it should "convert between bytes and nibbles" in {
    bytes2Nibbles(ByteString(1, 2)) is ByteString(0, 1, 0, 2)
    nibbles2Bytes(ByteString(0, 1, 0, 2)) is ByteString(1, 2)
    forAll { bytes: AVector[Byte] =>
      val bs = ByteString.fromArrayUnsafe(bytes.toArray)
      nibbles2Bytes(bytes2Nibbles(bs)) is bs
    }
  }

  behavior of "node serde"

  trait NodeFixture {
    val nibblesGen = for {
      length  <- Gen.choose[Int](0, 64)
      nibbles <- Gen.listOfN(length, Gen.choose[Byte](0, 15))
    } yield ByteString(nibbles.toArray)
    val leafGen = for {
      path      <- nibblesGen
      dataLengh <- Gen.choose[Int](0, 10)
      data      <- Gen.listOfN(dataLengh, Arbitrary.arbByte.arbitrary)
    } yield LeafNode(ByteString(path.toArray), ByteString(data.toArray))

    val keccak256Gen = Gen.const(()).map(_ => Hash.random)
    val branchGen = for {
      path     <- nibblesGen
      children <- Gen.listOfN(16, Gen.option(keccak256Gen))
    } yield BranchNode(ByteString(path.toArray), AVector.from(children))

    val nodeGen = Gen.oneOf(leafGen, branchGen)
  }

  it should "encode flag correctly" in {
    def test(length: Int, isLeaf: Boolean, flag: Int): Assertion = {
      assume(flag >= 0 && flag < 256)
      Node.SerdeNode.encodeFlag(length, isLeaf) is flag
      Node.SerdeNode.decodeFlag(flag) is ((length, isLeaf))
    }
    test(0, true, 0)
    test(0, false, 1)
    test(64, true, 128 + 0)
    test(63, true, 126 + 0)
    test(63, false, 126 + 1)
  }

  it should "encode nibbles correctly" in new NodeFixture {
    import Node.SerdeNode._

    forAll(nibblesGen) { nibbles =>
      decodeNibbles(encodeNibbles(nibbles), nibbles.length) is nibbles
      encodeNibbles(decodeNibbles(nibbles, nibbles.length * 2)) is nibbles
    }
  }

  it should "serde correctly" in new NodeFixture {
    forAll(nodeGen) { node => deserialize[Node](serialize[Node](node)) isE node }
  }

  behavior of "Merkle Patricia Trie"

  val genesisKey   = Hash.zero
  val genesisValue = Hash.zero
  val genesisNode = {
    val genesisPath = bytes2Nibbles(genesisKey.bytes)
    LeafNode(genesisPath, genesisValue.bytes)
  }

  def generateKV(keyPrefix: ByteString = ByteString.empty): (Hash, Hash) = {
    val key  = Hash.random
    val data = Hash.random
    (Hash.unsafe(keyPrefix ++ key.bytes.drop(keyPrefix.length)), data)
  }

  trait TrieFixture extends StorageFixture {
    val storage0 = newDBStorage()
    val db0      = newDB[Hash, SparseMerkleTrie.Node](storage0, RocksDBSource.ColumnFamily.All)
    var trie     = SparseMerkleTrie.unsafe[Hash, Hash](db0, genesisKey, genesisValue)

    val storage1  = newDBStorage()
    val db1       = newDB[Hash, SparseMerkleTrie.Node](storage1, RocksDBSource.ColumnFamily.All)
    var inMemTrie = SparseMerkleTrie.inMemoryUnsafe[Hash, Hash](db1, genesisKey, genesisValue)
  }

  def withTrieFixture[T](f: TrieFixture => T): TrieFixture =
    new TrieFixture {
      f(this)
      postTest()
    }

  it should "be able to create a trie" in withTrieFixture { fixture =>
    fixture.trie.rootHash is genesisNode.hash
    fixture.trie.getOpt(genesisKey).map(_.nonEmpty) isE true
  }

  it should "branch well" in withTrieFixture { fixture =>
    import fixture.trie

    val keys = (0 until 16).flatMap { i =>
      if (i equals genesisNode.path.head.toInt) {
        None
      } else {
        val prefix       = ByteString(i.toByte)
        val (key, value) = generateKV(prefix)
        trie = trie.put(key, value).rightValue
        Some(key)
      }
    }

    val rootNode = trie.getNode(trie.rootHash).rightValue
    rootNode match {
      case BranchNode(path, children) =>
        children.foreach(_.nonEmpty is true)
        path is ByteString(0)
      case _: LeafNode =>
        true is false
    }

    keys.foreach { key => trie.getOpt(key).map(_.nonEmpty) isE true }

    val allStored    = trie.getAll(ByteString.empty, Int.MaxValue).rightValue
    val allKeys      = allStored.map(_._1).toSet
    val expectedKeys = (keys :+ genesisKey).toSet
    allKeys is expectedKeys

    keys.foreach { key =>
      trie = trie.remove(key).rightValue
      trie.getOpt(key).map(_.isEmpty) isE true
    }

    trie.rootHash is genesisNode.hash
  }

  // scalastyle:off method.length
  def testRandomInsertions[T](initialTrie: SparseMerkleTrieBase[Hash, Hash, T]) = {
    var trie = initialTrie

    def update(f: => IOResult[T]) = f.rightValue match {
      case _: Unit => ()
      case newTrie: SparseMerkleTrieBase[_, _, _] =>
        trie = newTrie.asInstanceOf[SparseMerkleTrieBase[Hash, Hash, T]]
      case _ => ???
    }

    val keys = AVector.tabulate(1000) { _ =>
      val (key, value) = generateKV()
      update(trie.put(key, value))
      val trieHash1 = trie.rootHash
      update(trie.put(key, value)) // idempotent tests
      val trieHash2 = trie.rootHash
      trieHash2 is trieHash1
      key
    }

    (1 to 1001).foreach { k =>
      trie.getAll(ByteString.empty, k).rightValue.length is k
    }

    val filter0 = trie.getAll(ByteString.empty, 1001, (key, _) => key.hashCode() > 0).rightValue
    val filter1 = trie.getAll(ByteString.empty, 1001, (key, _) => key.hashCode() <= 0).rightValue
    filter0.length + filter1.length is 1001

    val filter2 = trie.getAll(ByteString.empty, 100, (key, _) => key.hashCode() > 0).rightValue
    val filter3 = trie.getAll(ByteString.empty, 100, (key, _) => key.hashCode() <= 0).rightValue
    (filter2.length + filter3.length > 100) is true
    (filter2.length + filter3.length <= 200) is true

    val samples = Set(keys.take(500).sample(), keys.drop(500).sample())
    trie.getAll(ByteString.empty, 1, (key, _) => samples.contains(key)).rightValue.length is 1
    trie.getAll(ByteString.empty, 2, (key, _) => samples.contains(key)).rightValue.length is 2
    trie.getAll(ByteString.empty, 3, (key, _) => samples.contains(key)).rightValue.length is 2

    keys.foreach { key =>
      trie.getOpt(key).map(_.nonEmpty) isE true
      trie.exists(key) isE true
    }

    keys.foreach { key =>
      val (_, value) = generateKV()
      update(trie.put(key, value))
    }

    (1 to 1001).foreach { k =>
      val (key, _) = generateKV()
      trie.getOpt(key).map(_.isEmpty) isE true
      trie.get(key).leftValue is a[IOError.KeyNotFound]
      trie.getAll(ByteString.empty, k).rightValue.length is k
    }
    keys.foreach { key =>
      trie.getOpt(key).map(_.nonEmpty) isE true
      trie.exists(key) isE true
    }

    keys.map { key =>
      update(trie.remove(key))
      trie.getOpt(key).map(_.isEmpty) isE true
      trie.exists(key) isE false
    }

    trie.rootHash is genesisNode.hash
  }

  it should "work for random insertions" in withTrieFixture { fixture =>
    testRandomInsertions(fixture.trie)
  }

  it should "work for random insertions with in memory trie" in withTrieFixture { fixture =>
    testRandomInsertions(fixture.inMemTrie)
  }

  it should "work for explicit examples" in new StorageFixture {
    val storage = newDBStorage()
    val db      = newDB[Hash, SparseMerkleTrie.Node](storage, RocksDBSource.ColumnFamily.All)
    val trie0   = SparseMerkleTrie.unsafe[Hash, Hash](db, genesisKey, genesisValue)

    val key0   = Hash.generate
    val key1   = Hash.generate
    val value0 = Hash.generate
    val value1 = Hash.generate
    val trie1  = trie0.put(key0, value0).rightValue
    trie1.rootHash isnot trie0.rootHash
    val trie2 = trie1.put(key0, value0).rightValue
    trie2.rootHash is trie1.rootHash
    val trie3 = trie2.put(key1, value1).rightValue
    trie3.rootHash isnot trie2.rootHash
    val trie4 = trie3.put(key1, value1).rightValue
    trie4.rootHash is trie3.rootHash
    val trie5 = trie4.remove(key1).rightValue
    trie5.rootHash is trie1.rootHash
    val trie6 = trie5.remove(key0).rightValue
    trie6.rootHash is trie0.rootHash
  }

  it should "write in batch" in withTrieFixture { fixture =>
    import fixture.{inMemTrie, trie}

    trie.rootHash is inMemTrie.rootHash

    info("Insert 2000 random key-value pairs")
    val pairs = AVector.tabulate(2000) { _ =>
      val (key, value) = generateKV()
      trie = trie.put(key, value).rightValue
      inMemTrie.put(key, value)
      key -> value
    }

    info("Remove 500 key-value pairs")
    pairs.take(500).foreach { case (key, _) =>
      trie = trie.remove(key).rightValue
      inMemTrie.remove(key)
    }

    info("Persist the merkle tree in batch")
    val persisted = inMemTrie.persistInBatch().rightValue
    persisted.rootHash is trie.rootHash

    info("Verify the final persisted merkle tree")
    pairs.take(500).foreach { case (key, _) =>
      persisted.exists(key) isE false
    }
    pairs.drop(500).foreach { case (key, value) =>
      persisted.exists(key) isE true
      persisted.get(key) isE value
    }
    trie.getAll(ByteString.empty, Int.MaxValue).rightValue.length is 1501
    persisted.getAll(ByteString.empty, Int.MaxValue).rightValue.length is 1501
  }

  it should "persist empty trie" in withTrieFixture { fixture =>
    fixture.inMemTrie.persistInBatch().rightValue.rootHash is fixture.trie.rootHash
  }
}

object SparseMerkleTrieSpec {
  import SparseMerkleTrie._

  def show[K: Serde, V: Serde](trie: SparseMerkleTrie[K, V]): Unit = {
    show(trie, Seq(trie.rootHash))
  }

  @tailrec
  def show[K: Serde, V: Serde](trie: SparseMerkleTrie[K, V], hashes: Seq[Hash]): Unit = {
    import org.alephium.util.Hex
    val newHashes = hashes.flatMap { hash =>
      val shortHash = Hex.toHexString(hash.bytes.take(4))
      trie.getNode(hash).toOption.get match {
        case BranchNode(path, children) =>
          val nChild = children.map(_.fold(0)(_ => 1)).sum
          print(s"($shortHash, ${path.length} $nChild); ")
          children.toSeq.flatten
        case LeafNode(path, data) =>
          val value = deserialize[V](data).toOption.get
          print(s"($shortHash, #${path.length}, $value)")
          Seq.empty
      }
    }
    print("\n")
    if (newHashes.nonEmpty) show(trie, newHashes)
  }
}
