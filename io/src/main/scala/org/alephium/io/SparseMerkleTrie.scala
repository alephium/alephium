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

import scala.collection.mutable

import akka.util.ByteString

import org.alephium.crypto.{Blake2b => Hash}
import org.alephium.serde._
import org.alephium.util.{AVector, Bytes}

object SparseMerkleTrie {
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
      assume(n <= path.length)
      BranchNode(path.drop(n), children)
    }
  }
  final case class LeafNode(path: ByteString, data: ByteString) extends Node {
    def preExtend(prefix: ByteString): Node = {
      LeafNode(prefix ++ path, data)
    }
    def preCut(n: Int): LeafNode = {
      assume(n <= path.length)
      LeafNode(path.drop(n), data)
    }
  }

  object Node {
    def branch(
        path: ByteString,
        nibble1: Int,
        node1: Node,
        nibble2: Int,
        node2: Node
    ): BranchNode = {
      assume(nibble1 != nibble2 && nibble1 < 16 && nibble2 < 16)
      val array = Array.fill[Option[Hash]](16)(None)
      array(nibble1) = Some(node1.hash)
      array(nibble2) = Some(node2.hash)
      BranchNode(path, AVector.unsafe(array))
    }

    implicit object SerdeNode extends Serde[Node] {
      def encodeFlag(length: Int, isLeaf: Boolean): Int = {
        assume(length >= 0)
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
        assume(flag >= 0)
        (flag >> 1, flag % 2 == 0)
      }

      def decodeNibbles(nibbles: ByteString, length: Int): ByteString = {
        assume(nibbles.length * 2 >= length && nibbles.length * 2 <= length + 1)
        val bytes = Array.tabulate(length) { i =>
          val byte = nibbles(i / 2)
          if (i % 2 == 0) getHighNibble(byte) else getLowNibble(byte)
        }
        ByteString.fromArrayUnsafe(bytes)
      }

      val childrenSerde: Serde[AVector[Option[Hash]]] = {
        fixedSizeSerde(16)
      }

      def _serialize(node: Node): ByteString =
        node match {
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

      override def _deserialize(input: ByteString): SerdeResult[Staging[Node]] = {
        intSerde._deserialize(input).flatMap { case Staging(flag, rest) =>
          val (length, isLeaf) = SerdeNode.decodeFlag(flag)
          val (left, right)    = rest.splitAt((length + 1) / 2)
          val path             = decodeNibbles(left, length)
          if (isLeaf) {
            bytestringSerde._deserialize(right).map { case Staging(data, rest1) =>
              Staging(LeafNode(path, data), rest1)
            }
          } else {
            childrenSerde._deserialize(right).map { case Staging(children, rest1) =>
              Staging(BranchNode(path, children), rest1)
            }
          }
        }
      }
    }
  }

  final case class TrieUpdateActions(
      nodeOpt: Option[Node],
      toDelete: AVector[Hash],
      toAdd: AVector[Node]
  )

  final case class SMTException(message: String) extends Exception(message)
  object SMTException {
    def keyNotFound(action: String): SMTException = SMTException("Key not found in " ++ action)
  }

  def getHighNibble(byte: Byte): Byte = {
    ((byte & 0xf0) >> 4).toByte
  }

  def getLowNibble(byte: Byte): Byte = {
    (byte & 0x0f).toByte
  }

  def getNibble(nibbles: ByteString, index: Int): Int = {
    assume(index >= 0 && index < nibbles.length)
    Bytes.toPosInt(nibbles(index))
  }

  def toNibbles[K: Serde](key: K): ByteString = {
    bytes2Nibbles(serialize[K](key))
  }

  def bytes2Nibbles(bytes: ByteString): ByteString = {
    val nibbles = Array.ofDim[Byte](bytes.length * 2)
    var index   = 0
    bytes.foreach { byte =>
      nibbles(2 * index) = getHighNibble(byte)
      nibbles(2 * index + 1) = getLowNibble(byte)
      index += 1
    }
    ByteString.fromArrayUnsafe(nibbles)
  }

  def nibbles2Bytes(nibbles: ByteString): ByteString = {
    assume(nibbles.length % 2 == 0)
    val bytes = Array.tabulate(nibbles.length / 2) { i =>
      val high = nibbles(2 * i)
      val low  = nibbles(2 * i + 1)
      ((high << 4) | low).toByte
    }
    ByteString.fromArrayUnsafe(bytes)
  }

  def unsafe[K: Serde, V: Serde](
      storage: KeyValueStorage[Hash, Node],
      genesisKey: K,
      genesisValue: V
  ): SparseMerkleTrie[K, V] = {
    val genesisPath = bytes2Nibbles(serialize(genesisKey))
    val genesisData = serialize(genesisValue)
    val genesisNode = LeafNode(genesisPath, genesisData)
    storage.putUnsafe(genesisNode.hash, genesisNode)
    new SparseMerkleTrie(genesisNode.hash, storage)
  }

  def apply[K: Serde, V: Serde](
      rootHash: Hash,
      storage: KeyValueStorage[Hash, Node]
  ): SparseMerkleTrie[K, V] =
    new SparseMerkleTrie[K, V](rootHash, storage)

  def inMemoryUnsafe[K: Serde, V: Serde](
      storage: KeyValueStorage[Hash, Node],
      genesisKey: K,
      genesisValue: V
  ): InMemorySparseMerkleTrie[K, V] = {
    val genesisPath = bytes2Nibbles(serialize(genesisKey))
    val genesisData = serialize(genesisValue)
    val genesisNode = LeafNode(genesisPath, genesisData)
    storage.putUnsafe(genesisNode.hash, genesisNode)
    new InMemorySparseMerkleTrie(genesisNode.hash, storage, mutable.Map.empty)
  }

  def inMemory[K: Serde, V: Serde](
      rootHash: Hash,
      storage: KeyValueStorage[Hash, Node]
  ): InMemorySparseMerkleTrie[K, V] =
    new InMemorySparseMerkleTrie[K, V](rootHash, storage, mutable.Map.empty)
}

abstract class SparseMerkleTrieBase[K: Serde, V: Serde, T] extends MutableKV[K, V, T] {
  import SparseMerkleTrie._

  def rootHash: Hash
  def getNode(hash: Hash): IOResult[Node]

  def get(key: K): IOResult[V] = {
    getOpt(key).flatMap {
      case None        => Left(IOError.keyNotFound(key, "SparseMerkleTrie.get"))
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
    val nibbles = SparseMerkleTrie.bytes2Nibbles(key)
    getOpt(rootHash, nibbles)
  }

  def getOpt(hash: Hash, nibbles: ByteString): IOResult[Option[ByteString]] = {
    getNode(hash).flatMap(getOpt(_, nibbles))
  }

  def getOpt(node: Node, nibbles: ByteString): IOResult[Option[ByteString]] = {
    node match {
      case BranchNode(path, children) =>
        assume(nibbles.length > path.length)
        if (path == nibbles.take(path.length)) {
          children(getNibble(nibbles, path.length)) match {
            case Some(childHash) =>
              getOpt(childHash, nibbles.drop(path.length + 1))
            case None =>
              Right(None)
          }
        } else {
          Right(None)
        }
      case LeafNode(path, data) =>
        if (path == nibbles) Right(Some(data)) else Right(None)
    }
  }

  def exists(key: K): IOResult[Boolean] = {
    existRaw(serialize[K](key))
  }

  def existRaw(key: ByteString): IOResult[Boolean] = {
    getOptRaw(key).map(_.nonEmpty)
  }

  protected def remove(hash: Hash, nibbles: ByteString): IOResult[TrieUpdateActions] = {
    getNode(hash).flatMap(remove(hash, _, nibbles))
  }

  @SuppressWarnings(Array("org.wartremover.warts.OptionPartial"))
  protected def remove(hash: Hash, node: Node, nibbles: ByteString): IOResult[TrieUpdateActions] = {
    node match {
      case node @ BranchNode(path, children) =>
        assume(nibbles.length > path.length)
        val nibble   = getNibble(nibbles, path.length)
        val childOpt = children(nibble)
        if (path == nibbles.take(path.length) && childOpt.nonEmpty) {
          val restNibbles = nibbles.drop(path.length + 1)
          val childHash   = childOpt.get
          remove(childHash, restNibbles) flatMap { result =>
            handleChildUpdateResult(hash, node, nibble, result)
          }
        } else {
          Left(IOError.keyNotFound(hash, "SparseMerkleTrie.remove"))
        }
      case leaf @ LeafNode(path, _) =>
        if (path == nibbles) {
          Right(TrieUpdateActions(None, AVector(leaf.hash), AVector.empty))
        } else {
          Left(IOError.keyNotFound(hash, "SparseMerkleTrie.remove"))
        }
    }
  }

  @SuppressWarnings(Array("org.wartremover.warts.OptionPartial"))
  def handleChildUpdateResult(
      branchHash: Hash,
      branchNode: BranchNode,
      nibble: Int,
      result: TrieUpdateActions
  ): IOResult[TrieUpdateActions] = {
    val children     = branchNode.children
    val childOptHash = result.nodeOpt.map(_.hash)
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
          if (oldChildOptHash.isEmpty) {
            result.toDelete :+ branchHash
          } else {
            result.toDelete ++ AVector(oldChildOptHash.get, branchHash)
          }
        Right(TrieUpdateActions(Some(newBranchNode), toDelete, result.toAdd :+ newBranchNode))
      } else {
        Right(TrieUpdateActions(Some(branchNode), AVector.empty, AVector.empty))
      }
    }
  }

  protected def put(
      hash: Hash,
      nibbles: ByteString,
      value: ByteString
  ): IOResult[TrieUpdateActions] = {
    assume(nibbles.nonEmpty)
    getNode(hash).flatMap(put(hash, _, nibbles, value))
  }

  protected def put(
      hash: Hash,
      node: Node,
      nibbles: ByteString,
      value: ByteString
  ): IOResult[TrieUpdateActions] = {
    val path = node.path
    assume(path.length <= nibbles.length)
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
          assume(path.length == nibbles.length)
          if (leaf.data == value) {
            Right(TrieUpdateActions(Some(leaf), AVector.empty, AVector.empty))
          } else {
            val newLeaf = LeafNode(path, value)
            Right(TrieUpdateActions(Some(newLeaf), AVector(leaf.hash), AVector(newLeaf)))
          }
      }
    } else {
      branch(hash, node, branchIndex, nibbles, value)
    }
  }

  protected def branch(
      hash: Hash,
      node: Node,
      branchIndex: Int,
      nibbles: ByteString,
      value: ByteString
  ): IOResult[TrieUpdateActions] = {
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

  def getAll(
      prefix: ByteString,
      maxNodes: Int
  ): IOResult[AVector[(K, V)]] = {
    val prefixNibbles = SparseMerkleTrie.bytes2Nibbles(prefix)
    getAllRaw(prefixNibbles, rootHash, ByteString.empty, maxNodes: Int, (_, _) => true)
  }

  def getAll(
      prefix: ByteString,
      maxNodes: Int,
      predicate: (K, V) => Boolean
  ): IOResult[AVector[(K, V)]] = {
    val prefixNibbles = SparseMerkleTrie.bytes2Nibbles(prefix)
    getAllRaw(prefixNibbles, rootHash, ByteString.empty, maxNodes: Int, predicate)
  }

  protected def getAllRaw(
      prefix: ByteString,
      hash: Hash,
      acc: ByteString,
      maxNodes: Int,
      predicate: (K, V) => Boolean
  ): IOResult[AVector[(K, V)]] = {
    if (prefix.isEmpty) {
      getAllRaw(hash, acc, maxNodes, predicate)
    } else {
      getNode(hash).flatMap(getAllRaw(prefix, _, acc, maxNodes, predicate))
    }
  }

  @SuppressWarnings(Array("org.wartremover.warts.IterableOps"))
  protected def getAllRaw(
      prefix: ByteString,
      node: Node,
      acc: ByteString,
      maxNodes: Int,
      predicate: (K, V) => Boolean
  ): IOResult[AVector[(K, V)]] = {
    node match {
      case n: BranchNode =>
        if (n.path.length >= prefix.length) {
          if (n.path.startsWith(prefix)) {
            getAllRaw(n, acc, maxNodes, predicate)
          } else {
            Right(AVector.empty)
          }
        } else {
          if (prefix.startsWith(n.path)) {
            val prefixRest = prefix.drop(n.path.length)
            val nibble     = prefixRest.head
            assume(nibble >= 0 && nibble < 16)
            n.children(nibble.toInt) match {
              case Some(child) =>
                getAllRaw(
                  prefixRest.tail,
                  child,
                  acc ++ n.path ++ ByteString(nibble),
                  maxNodes,
                  predicate
                )
              case None => Right(AVector.empty)
            }
          } else {
            Right(AVector.empty)
          }
        }
      case n: LeafNode =>
        if (n.path.take(prefix.length) == prefix) {
          deserializeKV(acc ++ n.path, n, predicate)
        } else {
          Right(AVector.empty)
        }
    }
  }

  protected def getAllRaw(
      hash: Hash,
      acc: ByteString,
      maxNodes: Int,
      predicate: (K, V) => Boolean
  ): IOResult[AVector[(K, V)]] = {
    getNode(hash).flatMap {
      case n: BranchNode => getAllRaw(n, acc, maxNodes, predicate)
      case n: LeafNode   => deserializeKV(acc ++ n.path, n, predicate)
    }
  }

  protected def getAllRaw(
      node: BranchNode,
      acc: ByteString,
      maxNodes: Int,
      predicate: (K, V) => Boolean
  ): IOResult[AVector[(K, V)]] = {
    node.children.foldWithIndexE(AVector.empty[(K, V)]) { case (leafNodes, childOpt, index) =>
      val restNodes = maxNodes - leafNodes.length
      childOpt match {
        case Some(child) if restNodes > 0 =>
          getAllRaw(child, acc ++ node.path ++ ByteString(index.toByte), restNodes, predicate)
            .map(leafNodes ++ _)
        case _ => Right(leafNodes)
      }
    }
  }

  def deserializeKV(
      nibbles: ByteString,
      node: LeafNode,
      predicate: (K, V) => Boolean
  ): IOResult[AVector[(K, V)]] = {
    val deser = for {
      key   <- deserialize[K](SparseMerkleTrie.nibbles2Bytes(nibbles))
      value <- deserialize[V](node.data)
    } yield {
      if (predicate(key, value)) {
        AVector(key -> value)
      } else {
        AVector.empty[(K, V)]
      }
    }
    deser.left.map(IOError.Serde)
  }
}

final class SparseMerkleTrie[K: Serde, V: Serde](
    val rootHash: Hash,
    storage: KeyValueStorage[Hash, SparseMerkleTrie.Node]
) extends SparseMerkleTrieBase[K, V, SparseMerkleTrie[K, V]] {
  import SparseMerkleTrie._

  def getNode(hash: Hash): IOResult[Node] = storage.get(hash)

  def applyActions(result: TrieUpdateActions): IOResult[SparseMerkleTrie[K, V]] = {
    result.toAdd
      .foreachE { node => storage.put(node.hash, node) }
      .map { _ =>
        result.nodeOpt match {
          case None       => this
          case Some(node) => new SparseMerkleTrie(node.hash, storage)
        }
      }
  }

  def remove(key: K): IOResult[SparseMerkleTrie[K, V]] = {
    removeRaw(serialize[K](key))
  }

  def removeRaw(key: ByteString): IOResult[SparseMerkleTrie[K, V]] = {
    val nibbles = SparseMerkleTrie.bytes2Nibbles(key)
    for {
      result <- remove(rootHash, nibbles)
      trie   <- applyActions(result)
    } yield trie
  }

  def put(key: K, value: V): IOResult[SparseMerkleTrie[K, V]] = {
    putRaw(serialize[K](key), serialize[V](value))
  }

  def putRaw(key: ByteString, value: ByteString): IOResult[SparseMerkleTrie[K, V]] = {
    val nibbles = SparseMerkleTrie.bytes2Nibbles(key)
    for {
      result <- put(rootHash, nibbles, value)
      trie   <- applyActions(result)
    } yield trie
  }

  def inMemory(): InMemorySparseMerkleTrie[K, V] = SparseMerkleTrie.inMemory(rootHash, storage)
}

final class InMemorySparseMerkleTrie[K: Serde, V: Serde](
    var rootHash: Hash,
    storage: KeyValueStorage[Hash, SparseMerkleTrie.Node],
    cache: mutable.Map[Hash, SparseMerkleTrie.Node]
) extends SparseMerkleTrieBase[K, V, Unit] {
  import SparseMerkleTrie._

  def getNode(hash: Hash): IOResult[Node] = {
    cache.get(hash) match {
      case Some(node) => Right(node)
      case None       => storage.get(hash)
    }
  }

  def applyActions(result: TrieUpdateActions): Unit = {
    result.toAdd.foreach(node => cache.put(node.hash, node))
//    result.toDelete.foreach(hash => cache -= hash) // this trades space for performance
    result.nodeOpt.foreach(node => rootHash = node.hash)
  }

  def remove(key: K): IOResult[Unit] = {
    removeRaw(serialize[K](key))
  }

  def removeRaw(key: ByteString): IOResult[Unit] = {
    val nibbles = SparseMerkleTrie.bytes2Nibbles(key)
    remove(rootHash, nibbles).map(applyActions)
  }

  def put(key: K, value: V): IOResult[Unit] = {
    putRaw(serialize[K](key), serialize[V](value))
  }

  def putRaw(key: ByteString, value: ByteString): IOResult[Unit] = {
    val nibbles = SparseMerkleTrie.bytes2Nibbles(key)
    put(rootHash, nibbles, value).map(applyActions)
  }

  def persistInBatch(): IOResult[SparseMerkleTrie[K, V]] = {
    @inline def preOrderTraversal(accumulatePut: (Hash, Node) => Unit): Unit = {
      val queue = mutable.Queue(rootHash)
      while (queue.nonEmpty) {
        val current = queue.dequeue()
        cache.get(current) match {
          case Some(node: BranchNode) =>
            accumulatePut(current, node)
            node.children.foreach { childOpt =>
              childOpt.foreach(queue.enqueue)
            }
          case Some(node: LeafNode) =>
            accumulatePut(current, node)
          case None => ()
        }
      }
    }

    storage
      .putBatch(preOrderTraversal)
      .map(_ => SparseMerkleTrie(rootHash, storage))
  }
}
