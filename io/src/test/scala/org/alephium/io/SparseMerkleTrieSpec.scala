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

  it should "convert" in {
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

  trait TrieFixture extends StorageFixture {
    val db   = newDB[Hash, SparseMerkleTrie.Node]
    var trie = SparseMerkleTrie.build[Hash, Hash](db, genesisKey, genesisValue)

    def generateKV(keyPrefix: ByteString = ByteString.empty): (Hash, Hash) = {
      val key  = Hash.random
      val data = Hash.random
      (Hash.unsafe(keyPrefix ++ key.bytes.drop(keyPrefix.length)), data)
    }
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
        val (key, value) = fixture.generateKV(prefix)
        trie = trie.put(key, value).toOption.get
        Some(key)
      }
    }

    val rootNode = trie.getNode(trie.rootHash).toOption.get
    rootNode match {
      case BranchNode(path, children) =>
        children.foreach(_.nonEmpty is true)
        path is ByteString(0)
      case _: LeafNode =>
        true is false
    }

    keys.foreach { key => trie.getOpt(key).map(_.nonEmpty) isE true }

    val allStored    = trie.getAll(ByteString.empty, Int.MaxValue).toOption.get
    val allKeys      = allStored.map(_._1).toSet
    val expectedKeys = (keys :+ genesisKey).toSet
    allKeys is expectedKeys

    keys.foreach { key =>
      trie = trie.remove(key).toOption.get
      trie.getOpt(key).map(_.isEmpty) isE true
    }

    trie.rootHash is genesisNode.hash
  }

  it should "work for random insertions" in withTrieFixture { fixture =>
    import fixture.trie

    val keys = AVector.tabulate(1000) { _ =>
      val (key, value) = fixture.generateKV()
      trie = trie.put(key, value).toOption.get
      trie = trie.put(key, value).toOption.get //idempotent tests
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
      trie.exist(key) isE true
    }

    keys.foreach { key =>
      val (_, value) = fixture.generateKV()
      trie = trie.put(key, value).toOption.get
    }

    (1 to 1001).foreach { k =>
      val (key, _) = fixture.generateKV()
      trie.getOpt(key).map(_.isEmpty) isE true
      trie.get(key).leftValue is a[IOError.KeyNotFound[_]]
      trie.getAll(ByteString.empty, k).rightValue.length is k
    }
    keys.foreach { key =>
      trie.getOpt(key).map(_.nonEmpty) isE true
      trie.exist(key) isE true
    }

    keys.map { key =>
      trie = trie.remove(key).toOption.get
      trie.getOpt(key).map(_.isEmpty) isE true
      trie.exist(key) isE false
    }

    trie.rootHash is genesisNode.hash
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
