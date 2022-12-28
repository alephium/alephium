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

package org.alephium.flow.mempool

import scala.collection.mutable
import scala.reflect.ClassTag

import org.alephium.util.{AVector, SimpleMap}

// KeyedFlow is an indexed data structure for network flow.
class KeyedFlow[K, N <: KeyedFlow.Node[K, N]](
    sourceNodeGroups: AVector[SimpleMap[K, N]],
    allNodes: SimpleMap[K, N]
) {
  def size: Int = allNodes.size

  def contains(key: K): Boolean = allNodes.contains(key)

  def get(key: K): Option[N] = allNodes.get(key)

  def unsafe(key: K): N = allNodes.unsafe(key)

  def takeSourceNodes[T: ClassTag](sourceGroup: Int, maxNum: Int, f: N => T): AVector[T] = {
    AVector.from(sourceNodeGroups(sourceGroup).values().map(f).take(maxNum))
  }

  def clear(): Unit = {
    sourceNodeGroups.foreach(_.clear())
    allNodes.clear()
  }

  @inline private def _addSourceNode(node: N): Unit = {
    sourceNodeGroups(node.getGroup()).put(node.key, node)
  }
  @inline private def _removeSourceNode(node: N): Unit = {
    sourceNodeGroups(node.getGroup()).remove(node.key)
    ()
  }

  def addNewNode(node: N): Unit = {
    val key = node.key
    node.getParents() match {
      case Some(parents) => parents.foreach(_.addChild(node))
      case None          => _addSourceNode(node)
    }
    node
      .getChildren()
      .foreach(_.foreach { child =>
        if (child.isSource()) {
          _removeSourceNode(child)
        }
        child.addParent(node)
      })
    allNodes.put(key, node)
    ()
  }

  def removeNodeAndAncestors(key: K, sideEffect: N => Unit): Unit = {
    allNodes.get(key).foreach(removeNodeAndAncestors(_, sideEffect))
  }

  @SuppressWarnings(Array("org.wartremover.warts.Recursion"))
  def removeNodeAndAncestors(node: N, sideEffect: N => Unit): Unit = {
    removeNodeFromGraph(node)
    sideEffect(node)
    node.getParents().foreach(_.foreach(removeNodeAndAncestors(_, sideEffect)))
  }

  private def removeNodeFromGraph(node: N): Unit = {
    allNodes.remove(node.key)
    node
      .getChildren()
      .foreach(_.foreach { child =>
        child.removeParent(node)
        if (child.isSource()) {
          _addSourceNode(child)
        }
      })
    node.getParents() match {
      case None => _removeSourceNode(node)
      case Some(parents) =>
        parents.foreach(_.removeChild(node))
    }
  }

  def removeSourceNode(key: K): Unit = {
    allNodes.get(key).foreach(removeSourceNode)
  }

  @inline private def removeSourceNode(node: N): Unit = {
    assume(node.isSource())
    val key = node.key
    allNodes.remove(key)
    _removeSourceNode(node)
    node
      .getChildren()
      .foreach(_.foreach { child =>
        child.removeParent(node)
        if (child.isSource()) {
          _addSourceNode(child)
        }
      })
  }

  def removeNodeAndDescendants(key: K, sideEffect: N => Unit): Unit = {
    allNodes.get(key).foreach(removeNodeAndDescendants(_, sideEffect))
  }

  @SuppressWarnings(Array("org.wartremover.warts.Recursion"))
  def removeNodeAndDescendants(node: N, sideEffect: N => Unit): Unit = {
    sideEffect(node)
    node.getChildren() match {
      case None =>
        removeSinkNode(node)
      case Some(children) =>
        while (children.nonEmpty) {
          removeNodeAndDescendants(children(children.length - 1), sideEffect) // remove the last
        }
        assume(children.isEmpty)
        // The node should be a sink now
        removeSinkNode(node)
    }
  }

  @inline private def removeSinkNode(node: N): Unit = {
    val key = node.key
    assume(node.isSink())
    node.getParents().foreach(_.foreach(_.removeChild(node)))
    // the node might be a source node
    if (node.isSource()) {
      _removeSourceNode(node)
    }
    allNodes.remove(key)
    ()
  }
}

object KeyedFlow {
  trait Node[EK, EN <: Node[EK, EN]] {
    var _parents: Option[mutable.ArrayBuffer[EN]]
    var _children: Option[mutable.ArrayBuffer[EN]]

    def key: EK
    def getGroup(): Int
    def getParents(): Option[mutable.ArrayBuffer[EN]]  = _parents
    def getChildren(): Option[mutable.ArrayBuffer[EN]] = _children

    def addParent(parent: EN): Unit = {
      addToBuffer[EK, EN](_parents, _parents = _, parent)
    }
    def removeParent(node: EN): Unit = {
      removeFromBuffer[EK, EN](_parents, _parents = _, node)
    }

    def addChild(child: EN): Unit = {
      addToBuffer[EK, EN](_children, _children = _, child)
    }
    def removeChild(child: EN): Unit = {
      removeFromBuffer[EK, EN](_children, _children = _, child)
    }

    def isSource(): Boolean = _parents.isEmpty

    def isSink(): Boolean = _children.isEmpty
  }

  @inline def addToBuffer[K, EN <: Node[K, EN]](
      getter: => Option[mutable.ArrayBuffer[EN]],
      setter: Option[mutable.ArrayBuffer[EN]] => Unit,
      node: EN
  ): Unit = {
    val buffer = getter
    buffer match {
      case Some(nodes) => nodes.addOne(node)
      case None        => setter(Some(mutable.ArrayBuffer(node)))
    }
  }

  @inline def removeFromBuffer[K, EN <: Node[K, EN]](
      getter: => Option[mutable.ArrayBuffer[EN]],
      setter: Option[mutable.ArrayBuffer[EN]] => Unit,
      node: EN
  ): Unit = {
    val buffer = getter
    buffer match {
      case Some(nodes) =>
        remove(nodes, node)
        if (nodes.isEmpty) {
          setter(None)
        }
      case None => ()
    }
  }

  @inline def remove[T <: AnyRef](buffer: mutable.ArrayBuffer[T], node: T): Unit = {
    val index = buffer.indexWhere(_ eq node)
    if (index >= 0) {
      val lastIndex = buffer.length - 1
      // swap the element to be removed with the last element
      if (index < lastIndex) {
        val tmp = buffer(index)
        buffer(index) = buffer(lastIndex)
        buffer(lastIndex) = tmp
      }
      buffer.dropRightInPlace(1)
    }
  }
}
