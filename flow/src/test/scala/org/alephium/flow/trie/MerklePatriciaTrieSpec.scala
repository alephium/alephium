package org.alephium.flow.trie

import akka.util.ByteString
import org.scalacheck.{Arbitrary, Gen}
import org.scalatest.Assertion

import org.alephium.flow.io.{RocksDBKeyValueStorage, RocksDBSource}
import org.alephium.protocol.ALF.Hash
import org.alephium.serde._
import org.alephium.util.{AlephiumSpec, AVector, Files}

class MerklePatriciaTrieSpec extends AlephiumSpec {
  import MerklePatriciaTrie._

  behavior of "nibbles calculation"

  it should "calculate correct nibbles" in {
    def test(input: Byte, high: Byte, low: Byte): Assertion = {
      MerklePatriciaTrie.getLowNibble(input) is low
      MerklePatriciaTrie.getHighNibble(input) is high
    }
    test(0x00.toByte, 0x00.toByte, 0x00.toByte)
    test(0x0F.toByte, 0x00.toByte, 0x0F.toByte)
    test(0xF0.toByte, 0x0F.toByte, 0x00.toByte)
    test(0xFF.toByte, 0x0F.toByte, 0x0F.toByte)
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
      assert(flag >= 0 && flag < 256)
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
    forAll(nodeGen) { node =>
      deserialize[Node](serialize[Node](node)) isE node
    }
  }

  behavior of "Merkle Patricia Trie"

  val genesisKey = Hash.zero.bytes
  val genesisNode = {
    val genesisPath = bytes2Nibbles(genesisKey)
    LeafNode(genesisPath, ByteString.empty)
  }

  trait TrieFixture {
    import RocksDBSource.ColumnFamily

    private val tmpdir = Files.tmpDir
    private val dbname = "trie"
    private val dbPath = tmpdir.resolve(dbname)

    private val storage =
      RocksDBSource.openUnsafe(dbPath, RocksDBSource.Compaction.HDD)

    val db   = RocksDBKeyValueStorage[Hash, MerklePatriciaTrie.Node](storage, ColumnFamily.Trie)
    var trie = MerklePatriciaTrie.create(db, genesisNode)

    def generateKV(keyPrefix: ByteString = ByteString.empty): (ByteString, ByteString) = {
      val key  = Hash.random.bytes
      val data = ByteString.fromString(Gen.alphaStr.sample.get)
      (keyPrefix ++ key.drop(keyPrefix.length), data)
    }

    protected def postTest(): Assertion = {
      storage.dESTROY().isRight is true
    }
  }

  def withTrieFixture[T](f: TrieFixture => T): TrieFixture = new TrieFixture {
    f(this)
    postTest()
  }

  it should "be able to create a trie" in withTrieFixture { fixture =>
    fixture.trie.rootHash is genesisNode.hash
    fixture.trie.getOptRaw(genesisKey).map(_.nonEmpty) isE true
  }

  it should "branch well" in withTrieFixture { fixture =>
    import fixture.trie

    val keys = (0 until 16).flatMap { i =>
      if (i equals genesisNode.path.head.toInt) None
      else {
        val prefix       = ByteString(i.toByte)
        val (key, value) = fixture.generateKV(prefix)
        trie = trie.putRaw(key, value).toOption.get
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

    keys.foreach { key =>
      trie.getOptRaw(key).map(_.nonEmpty) isE true
    }

    val allStored    = trie.getAllRaw(ByteString.empty).toOption.get
    val allKeys      = allStored.map(_._1).toArray.sortBy(_.hashCode())
    val expectedKeys = (keys :+ genesisKey).toArray.sortBy(_.hashCode())
    allKeys is expectedKeys

    keys.foreach { key =>
      trie = trie.removeRaw(key).toOption.get
      trie.getOptRaw(key).map(_.isEmpty) isE true
    }

    trie.rootHash is genesisNode.hash
  }

  it should "work for random insertions" in withTrieFixture { fixture =>
    import fixture.trie

    val keys = AVector.tabulate(100) { _ =>
      val (key, value) = fixture.generateKV()
      trie = trie.putRaw(key, value).toOption.get
      key
    }

    keys.map { key =>
      trie.getOptRaw(key).map(_.nonEmpty) isE true
    }

    keys.map { key =>
      trie = trie.removeRaw(key).toOption.get
      trie.getOptRaw(key).map(_.isEmpty) isE true
    }

    trie.rootHash is genesisNode.hash
  }

  def show(trie: MerklePatriciaTrie): Unit = {
    show(trie, Seq(trie.rootHash))
  }

  def show(trie: MerklePatriciaTrie, hashes: Seq[Hash]): Unit = {
    import org.alephium.util.Hex
    val newHashes = hashes.flatMap { hash =>
      val shortHash = Hex.toHexString(hash.bytes.take(4))
      trie.getNode(hash).toOption.get match {
        case BranchNode(path, children) =>
          val nChild = children.map(_.fold(0)(_ => 1)).sum
          print(s"($shortHash, ${path.length} $nChild); ")
          children.toArray.toSeq.flatten
        case LeafNode(path, _) =>
          print(s"($shortHash, ${path.length} leaf)")
          Seq.empty
      }
    }
    print("\n")
    if (newHashes.nonEmpty) show(trie, newHashes)
  }
}
