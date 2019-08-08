package org.alephium.flow.trie

import akka.util.ByteString
import org.alephium.crypto.Keccak256
import org.alephium.flow.io.{HeaderDB, RocksDBStorage}
import org.alephium.serde._
import org.alephium.util.{AVector, AlephiumSpec, Files}
import org.scalacheck.{Arbitrary, Gen}
import org.scalatest.Assertion
import org.scalatest.EitherValues._

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

    val keccak256Gen = Gen.const(()).map(_ => Keccak256.random)
    val branchGen = for {
      path     <- nibblesGen
      children <- Gen.listOfN(16, Gen.option(keccak256Gen))
    } yield BranchNode(ByteString(path.toArray), AVector.from(children))

    val nodeGen = Gen.oneOf(leafGen, branchGen)
  }

  it should "encode flag correctly" in {
    def test(length: Int, isLeaf: Boolean, flag: Int): Assertion = {
      assert(flag >= 0 && flag < 256)
      Node.SerdeNode.encodeFlag(length, isLeaf) is flag.toByte
      Node.SerdeNode.decodeFlag(flag.toByte) is ((length, isLeaf))
    }
    test(0, true, 128)
    test(0, false, 0)
    test(64, true, 128 + 64)
    test(63, true, 128 + 63)
    test(63, false, 63)
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
      deserialize[Node](serialize[Node](node)).right.value is node
    }
  }

  behavior of "Merkle Patricia Trie"

  trait TrieFixture {
    import RocksDBStorage.{ColumnFamily, Settings}

    private val tmpdir = Files.tmpDir
    private val dbname = "trie"
    private val dbPath = tmpdir.resolve(dbname)

    private val storage =
      RocksDBStorage.openUnsafe(dbPath, RocksDBStorage.Compaction.HDD)

    val db   = HeaderDB(storage, ColumnFamily.Trie, Settings.readOptions)
    val trie = MerklePatriciaTrie.create(db)

    def generateKV(keyPrefix: ByteString = ByteString.empty): (Keccak256, ByteString) = {
      val key  = Keccak256.random.bytes
      val data = ByteString.fromString(Gen.alphaStr.sample.get)
      (Keccak256.unsafeFrom(keyPrefix ++ key.drop(keyPrefix.length)), data)
    }

    protected def postTest(): Assertion = {
      storage.close()
      RocksDBStorage.dESTROY(dbPath).isRight is true
    }
  }

  def withTrieFixture[T](f: TrieFixture => T): TrieFixture = new TrieFixture {
    f(this)
    postTest()
  }

  it should "be able to create a trie" in withTrieFixture { fixture =>
    fixture.trie.rootHash is genesisNode.hash
    fixture.trie.getOptRaw(genesisKey).right.value.nonEmpty is true
  }

  it should "branch well" in withTrieFixture { fixture =>
    import fixture.trie

    val keys = (0 until 16).flatMap { i =>
      if (i == genesisNode.path.head.toInt) None
      else {
        val prefix       = ByteString(i.toByte)
        val (key, value) = fixture.generateKV(prefix)
        trie.putRaw(key, value)
        Some(key)
      }
    }

    val rootNode = trie.getNode(trie.rootHash).right.value
    rootNode match {
      case BranchNode(path, children) =>
        children.foreach(_.nonEmpty is true)
        path is ByteString(0)
      case _: LeafNode =>
        true is false
    }

    keys.foreach { key =>
      trie.getOptRaw(key).right.value.nonEmpty is true
    }

    keys.foreach { key =>
      trie.remove(key).isRight is true
      trie.getOptRaw(key).right.value.isEmpty is true
    }

    trie.rootHash is genesisNode.hash
  }

  it should "work for random insertions" in withTrieFixture { fixture =>
    import fixture.trie

    val keys = AVector.tabulate(100) { _ =>
      val (key, value) = fixture.generateKV()
      trie.putRaw(key, value).isRight is true
      key
    }

    keys.map { key =>
      trie.getOptRaw(key).right.value.nonEmpty is true
    }

    keys.map { key =>
      trie.remove(key).isRight is true
      trie.getOptRaw(key).right.value.isEmpty is true
    }

    trie.rootHash is genesisNode.hash
  }

  def show(trie: MerklePatriciaTrie): Unit = {
    show(trie, Seq(trie.rootHash))
  }

  def show(trie: MerklePatriciaTrie, hashes: Seq[Keccak256]): Unit = {
    import org.alephium.util.Hex
    val newHashes = hashes.flatMap { hash =>
      val shortHash = Hex.toHexString(hash.bytes.take(4))
      trie.getNode(hash).right.value match {
        case BranchNode(_, children) =>
          val nChild = children.map(_.fold(0)(_ => 1)).sum
          print(s"($shortHash, $nChild); ")
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
