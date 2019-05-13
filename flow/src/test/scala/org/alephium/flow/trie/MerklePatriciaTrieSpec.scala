package org.alephium.flow.trie

import akka.util.ByteString
import org.alephium.crypto.Keccak256
import org.alephium.serde._
import org.alephium.util.{AVector, AlephiumSpec}
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
}
