package org.alephium.io

import akka.util.ByteString

import org.alephium.crypto.{Keccak256 => Hash}
import org.alephium.serde._
import org.alephium.util.{AVector, Bytes}

object MerklePatriciaTrie {
  /* branch [encodedPath, v0, ..., v15]
   * leaf   [encodedPath, data]
   * the length of encodedPath varies from 0 to 64
   * encoding flag byte = path length (7 bits) ++ type (1 bit)
   */
  sealed trait Node {
    lazy val serialized: ByteString = Node.SerdeNode._serialize(this)
    lazy val hash: Hash             = Hash.hash(serialized)

    def path: ByteString

    def preExtend(prefix: ByteString): Node

    def preCut(n: Int): Node
  }
  final case class BranchNode(path: ByteString, children: AVector[Option[Hash]]) extends Node {
    def replace(nibble: Int, childHash: Hash): BranchNode = {
      BranchNode(path, children.replace(nibble, Some(childHash)))
    }
    def preExtend(prefix: ByteString): BranchNode = {
      BranchNode(prefix ++ path, children)
    }
    def preCut(n: Int): BranchNode = {
      assert(n <= path.length)
      BranchNode(path.drop(n), children)
    }
  }
  final case class LeafNode(path: ByteString, data: ByteString) extends Node {
    def preExtend(prefix: ByteString): Node = {
      LeafNode(prefix ++ path, data)
    }
    def preCut(n: Int): LeafNode = {
      assert(n <= path.length)
      LeafNode(path.drop(n), data)
    }
  }

  object Node {
    def branch(path: ByteString,
               nibble1: Int,
               node1: Node,
               nibble2: Int,
               node2: Node): BranchNode = {
      assert(nibble1 != nibble2 && nibble1 < 16 && nibble2 < 16)
      val array = Array.fill[Option[Hash]](16)(None)
      array(nibble1) = Some(node1.hash)
      array(nibble2) = Some(node2.hash)
      BranchNode(path, AVector.unsafe(array))
    }

    implicit object SerdeNode extends Serde[Node] {
      def encodeFlag(length: Int, isLeaf: Boolean): Int = {
        assert(length >= 0)
        (length << 1) + (if (isLeaf) 0 else 1)
      }

      def encodeNibbles(path: ByteString): ByteString = {
        val length = (path.length + 1) / 2
        val nibbles = Array.tabulate(length) { i =>
          var twoNibbles = path(2 * i) << 4
          if (i < length - 1 || path.length % 2 == 0) {
            twoNibbles += path(2 * i + 1)
          }
          twoNibbles.toByte
        }
        ByteString.fromArrayUnsafe(nibbles)
      }

      def decodeFlag(flag: Int): (Int, Boolean) = {
        assert(flag >= 0)
        (flag >> 1, flag % 2 == 0)
      }

      def decodeNibbles(nibbles: ByteString, length: Int): ByteString = {
        assert(nibbles.length * 2 >= length && nibbles.length * 2 <= length + 1)
        val bytes = Array.tabulate(length) { i =>
          val byte = nibbles(i / 2)
          if (i % 2 == 0) getHighNibble(byte) else getLowNibble(byte)
        }
        ByteString.fromArrayUnsafe(bytes)
      }

      val childrenSerde: Serde[AVector[Option[Hash]]] = {
        fixedSizeSerde(16)
      }

      def _serialize(node: Node): ByteString = node match {
        case n: BranchNode =>
          val flag     = SerdeNode.encodeFlag(n.path.length, isLeaf = false)
          val nibbles  = encodeNibbles(n.path)
          val children = childrenSerde.serialize(n.children)
          (intSerde.serialize(flag) ++ nibbles) ++ children
        case n: LeafNode =>
          val flag    = SerdeNode.encodeFlag(n.path.length, isLeaf = true)
          val nibbles = encodeNibbles(n.path)
          (intSerde.serialize(flag) ++ nibbles) ++ bytestringSerde.serialize(n.data)
      }

      override def serialize(node: Node): ByteString = node.serialized

      override def _deserialize(input: ByteString): SerdeResult[(Node, ByteString)] = {
        intSerde._deserialize(input).flatMap {
          case (flag, rest) =>
            val (length, isLeaf) = SerdeNode.decodeFlag(flag)
            val (left, right)    = rest.splitAt((length + 1) / 2)
            val path             = decodeNibbles(left, length)
            if (isLeaf) {
              bytestringSerde._deserialize(right).map {
                case (data, rest1) => (LeafNode(path, data), rest1)
              }
            } else {
              childrenSerde._deserialize(right).map {
                case (children, rest1) => (BranchNode(path, children), rest1)
              }
            }
        }
      }
    }
  }

  final case class TrieUpdateActions(newNodeOpt: Option[Node],
                                     toDelete: AVector[Hash],
                                     toAdd: AVector[Node])
  final case class MPTException(message: String) extends Exception(message)
  object MPTException {
    def keyNotFound(action: String): MPTException = MPTException("Key not found in " ++ action)
  }

  private val removalNoKey = IOError.Other(MPTException.keyNotFound("removal"))

  def getHighNibble(byte: Byte): Byte = {
    ((byte & 0xF0) >> 4).toByte
  }

  def getLowNibble(byte: Byte): Byte = {
    (byte & 0x0F).toByte
  }

  def getNibble(nibbles: ByteString, index: Int): Int = {
    assume(index >= 0 && index < nibbles.length)
    Bytes.toPosInt(nibbles(index))
  }

  def toNibbles[K: Serde](key: K): ByteString = {
    bytes2Nibbles(serialize[K](key))
  }

  def bytes2Nibbles(bytes: ByteString): ByteString = {
    ByteString(bytes.flatMap { byte =>
      ByteString(getHighNibble(byte), getLowNibble(byte))
    })
  }

  def nibbles2Bytes(nibbles: ByteString): ByteString = {
    assert(nibbles.length % 2 == 0)
    val bytes = Array.tabulate(nibbles.length / 2) { i =>
      val high = nibbles(2 * i)
      val low  = nibbles(2 * i + 1)
      ((high << 4) | low).toByte
    }
    ByteString.fromArrayUnsafe(bytes)
  }

  def build[K: Serde, V: Serde](storage: KeyValueStorage[Hash, Node],
                                genesisKey: K,
                                genesisValue: V): MerklePatriciaTrie[K, V] = {
    val genesisPath = bytes2Nibbles(serialize(genesisKey))
    val genesisData = serialize(genesisValue)
    val genesisNode = LeafNode(genesisPath, genesisData)
    storage.put(genesisNode.hash, genesisNode)
    new MerklePatriciaTrie(genesisNode.hash, storage)
  }

  def apply[K: Serde, V: Serde](rootHash: Hash,
                                storage: KeyValueStorage[Hash, Node]): MerklePatriciaTrie[K, V] =
    new MerklePatriciaTrie[K, V](rootHash, storage)
}

// TODO: batch mode
final class MerklePatriciaTrie[K: Serde, V: Serde](
    val rootHash: Hash,
    storage: KeyValueStorage[Hash, MerklePatriciaTrie.Node]
) {
  import MerklePatriciaTrie._

  def applyActions(result: TrieUpdateActions): IOResult[MerklePatriciaTrie[K, V]] = {
    result.toAdd
      .foreachE { node =>
        storage.put(node.hash, node)
      }
      .map { _ =>
        result.newNodeOpt match {
          case None       => this
          case Some(node) => new MerklePatriciaTrie(node.hash, storage)
        }
      }
  }

  def get(key: K): IOResult[V] = {
    getOpt(key).flatMap {
      case None        => Left(IOError.KeyNotFound(key))
      case Some(value) => Right(value)
    }
  }

  def getOpt(key: K): IOResult[Option[V]] = {
    getOptRaw(serialize[K](key)).flatMap {
      case None => Right(None)
      case Some(bytes) =>
        deserialize[V](bytes) match {
          case Left(error)  => Left(IOError.Serde(error))
          case Right(value) => Right(Some(value))
        }
    }
  }

  def getOptRaw(key: ByteString): IOResult[Option[ByteString]] = {
    val nibbles = MerklePatriciaTrie.bytes2Nibbles(key)
    getOpt(rootHash, nibbles)
  }

  def getOpt(hash: Hash, nibbles: ByteString): IOResult[Option[ByteString]] = {
    getNode(hash).flatMap(getOpt(_, nibbles))
  }

  def getOpt(node: Node, nibbles: ByteString): IOResult[Option[ByteString]] = {
    node match {
      case BranchNode(path, children) =>
        assert(nibbles.length > path.length)
        if (path == nibbles.take(path.length)) {
          children(getNibble(nibbles, path.length)) match {
            case Some(childHash) =>
              getOpt(childHash, nibbles.drop(path.length + 1))
            case None =>
              Right(None)
          }
        } else Right(None)
      case LeafNode(path, data) =>
        if (path == nibbles) Right(Some(data)) else Right(None)
    }
  }

  def getNode(hash: Hash): IOResult[Node] = storage.get(hash)

  def remove(key: K): IOResult[MerklePatriciaTrie[K, V]] = {
    removeRaw(serialize[K](key))
  }

  def removeRaw(key: ByteString): IOResult[MerklePatriciaTrie[K, V]] = {
    val nibbles = MerklePatriciaTrie.bytes2Nibbles(key)
    for {
      result <- remove(rootHash, nibbles)
      trie   <- applyActions(result)
    } yield trie
  }

  private def remove(hash: Hash, nibbles: ByteString): IOResult[TrieUpdateActions] = {
    getNode(hash).flatMap(remove(hash, _, nibbles))
  }

  @SuppressWarnings(Array("org.wartremover.warts.OptionPartial"))
  private def remove(hash: Hash, node: Node, nibbles: ByteString): IOResult[TrieUpdateActions] = {
    node match {
      case node @ BranchNode(path, children) =>
        assert(nibbles.length > path.length)
        val nibble   = getNibble(nibbles, path.length)
        val childOpt = children(nibble)
        if (path == nibbles.take(path.length) && childOpt.nonEmpty) {
          val restNibbles = nibbles.drop(path.length + 1)
          val childHash   = childOpt.get
          remove(childHash, restNibbles) flatMap { result =>
            handleChildUpdateResult(hash, node, nibble, result)
          }
        } else {
          Left(MerklePatriciaTrie.removalNoKey)
        }
      case leaf @ LeafNode(path, _) =>
        if (path == nibbles) {
          Right(TrieUpdateActions(None, AVector(leaf.hash), AVector.empty))
        } else {
          Left(MerklePatriciaTrie.removalNoKey)
        }
    }
  }

  @SuppressWarnings(Array("org.wartremover.warts.OptionPartial"))
  def handleChildUpdateResult(branchHash: Hash,
                              branchNode: BranchNode,
                              nibble: Int,
                              result: TrieUpdateActions): IOResult[TrieUpdateActions] = {
    val children     = branchNode.children
    val childOptHash = result.newNodeOpt.map(_.hash)
    val newChildren  = children.replace(nibble, childOptHash)
    if (childOptHash.isEmpty && newChildren.map(_.fold(0)(_ => 1)).sum == 1) {
      val onlyChildIndex = newChildren.indexWhere(_.nonEmpty)
      val onlyChildHash  = children(onlyChildIndex).get
      getNode(onlyChildHash) map { onlyChild =>
        val newNode  = onlyChild.preExtend(branchNode.path ++ ByteString(onlyChildIndex.toByte))
        val toDelete = result.toDelete ++ AVector(onlyChildHash, branchHash)
        TrieUpdateActions(Some(newNode), toDelete, result.toAdd :+ newNode)
      }
    } else {
      val oldChildOptHash = children(nibble)
      if (oldChildOptHash != childOptHash) {
        val newBranchNode = BranchNode(branchNode.path, newChildren)
        val toDelete =
          if (oldChildOptHash.isEmpty) result.toDelete :+ branchHash
          else result.toDelete ++ AVector(oldChildOptHash.get, branchHash)
        Right(TrieUpdateActions(Some(newBranchNode), toDelete, result.toAdd :+ newBranchNode))
      } else {
        Right(TrieUpdateActions(None, result.toDelete, result.toAdd))
      }
    }
  }

  def put(key: K, value: V): IOResult[MerklePatriciaTrie[K, V]] = {
    putRaw(serialize[K](key), serialize[V](value))
  }

  def putRaw(key: ByteString, value: ByteString): IOResult[MerklePatriciaTrie[K, V]] = {
    val nibbles = MerklePatriciaTrie.bytes2Nibbles(key)
    for {
      result <- put(rootHash, nibbles, value)
      trie   <- applyActions(result)
    } yield trie
  }

  private def put(hash: Hash,
                  nibbles: ByteString,
                  value: ByteString): IOResult[TrieUpdateActions] = {
    assume(nibbles.nonEmpty)
    getNode(hash).flatMap(put(hash, _, nibbles, value))
  }

  private def put(hash: Hash,
                  node: Node,
                  nibbles: ByteString,
                  value: ByteString): IOResult[TrieUpdateActions] = {
    val path = node.path
    assert(path.length <= nibbles.length)
    val branchIndex = node.path.indices.indexWhere(i => nibbles(i) != path(i))
    if (branchIndex == -1) {
      node match {
        case branchNode @ BranchNode(_, children) =>
          val nibble       = getNibble(nibbles, path.length)
          val nibblesRight = nibbles.drop(path.length + 1)
          children(nibble) match {
            case Some(childHash) =>
              put(childHash, nibblesRight, value) flatMap { result =>
                handleChildUpdateResult(hash, branchNode, nibble, result)
              }
            case None =>
              val newLeaf   = LeafNode(nibblesRight, value)
              val newBranch = branchNode.replace(nibble, newLeaf.hash)
              Right(TrieUpdateActions(Some(newBranch), AVector(hash), AVector(newBranch, newLeaf)))
          }
        case leaf: LeafNode =>
          assert(path.length == nibbles.length)
          val newLeaf = LeafNode(path, value)
          Right(TrieUpdateActions(Some(newLeaf), AVector(leaf.hash), AVector(newLeaf)))
      }
    } else {
      branch(hash, node, branchIndex, nibbles, value)
    }
  }

  private def branch(hash: Hash,
                     node: Node,
                     branchIndex: Int,
                     nibbles: ByteString,
                     value: ByteString): IOResult[TrieUpdateActions] = {
    val path         = node.path
    val prefix       = path.take(branchIndex)
    val nibble1      = getNibble(path, branchIndex)
    val node1        = node.preCut(branchIndex + 1)
    val nibblesRight = nibbles.drop(branchIndex + 1)
    val nibble2      = getNibble(nibbles, branchIndex)
    val newLeaf      = LeafNode(nibblesRight, value)
    val branchNode   = Node.branch(prefix, nibble1, node1, nibble2, newLeaf)

    val toAdd = AVector[Node](branchNode, node1, newLeaf)
    Right(TrieUpdateActions(Some(branchNode), AVector(hash), toAdd))
  }

  def getAll(prefix: ByteString): IOResult[AVector[(K, V)]] = {
    val prefixNibbles = MerklePatriciaTrie.bytes2Nibbles(prefix)
    getAllRaw(prefixNibbles, rootHash, ByteString.empty).flatMap { dataVec =>
      dataVec.mapE {
        case (nibbles, leaf) =>
          val deser = for {
            key   <- deserialize[K](MerklePatriciaTrie.nibbles2Bytes(nibbles))
            value <- deserialize[V](leaf.data)
          } yield (key, value)
          deser.left.map(IOError.Serde)
      }
    }
  }

  def getAllRaw(prefix: ByteString): IOResult[AVector[(ByteString, ByteString)]] = {
    val prefixNibbles = MerklePatriciaTrie.bytes2Nibbles(prefix)
    getAllRaw(prefixNibbles, rootHash, ByteString.empty).map(_.map {
      case (nibbles, leaf) => (MerklePatriciaTrie.nibbles2Bytes(nibbles), leaf.data)
    })
  }

  protected def getAllRaw(prefix: ByteString,
                          hash: Hash,
                          acc: ByteString): IOResult[AVector[(ByteString, LeafNode)]] = {
    if (prefix.isEmpty) {
      getAllRaw(hash, acc)
    } else {
      getNode(hash).flatMap(getAllRaw(prefix, _, acc))
    }
  }

  @SuppressWarnings(Array("org.wartremover.warts.TraversableOps"))
  protected def getAllRaw(prefix: ByteString,
                          node: Node,
                          acc: ByteString): IOResult[AVector[(ByteString, LeafNode)]] = {
    node match {
      case n: BranchNode =>
        if (n.path.length >= prefix.length) {
          if (n.path.startsWith(prefix)) getAllRaw(n, acc) else Right(AVector.empty)
        } else {
          if (prefix.startsWith(n.path)) {
            val prefixRest = prefix.drop(n.path.length)
            val nibble     = prefixRest.head
            assert(nibble >= 0 && nibble < 16)
            n.children(nibble.toInt) match {
              case Some(child) =>
                getAllRaw(prefixRest.tail, child, acc ++ n.path ++ ByteString(nibble))
              case None => Right(AVector.empty)
            }
          } else Right(AVector.empty)
        }
      case n: LeafNode =>
        if (n.path.take(prefix.length) == prefix) {
          Right(AVector(acc ++ n.path -> n))
        } else {
          Right(AVector.empty)
        }
    }
  }

  protected def getAllRaw(hash: Hash,
                          acc: ByteString): IOResult[AVector[(ByteString, LeafNode)]] = {
    getNode(hash).flatMap {
      case n: BranchNode =>
        getAllRaw(n, acc)
      case n: LeafNode => Right(AVector(acc ++ n.path -> n))
    }
  }

  protected def getAllRaw(node: BranchNode,
                          acc: ByteString): IOResult[AVector[(ByteString, LeafNode)]] = {
    node.children.flatMapWithIndexE { (childOpt, index) =>
      childOpt match {
        case Some(child) => getAllRaw(child, acc ++ node.path ++ ByteString(index.toByte))
        case None        => Right(AVector.empty)
      }
    }
  }
}
