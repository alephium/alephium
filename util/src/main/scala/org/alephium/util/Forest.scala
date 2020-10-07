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

package org.alephium.util

import scala.collection.mutable

import org.alephium.util.Forest.Node

object Forest {
  // Note: the parent node should comes first in values; otherwise return None
  def tryBuild[K, T](values: AVector[T], toKey: T => K, toParent: T => K): Option[Forest[K, T]] = {
    val rootParents = mutable.HashMap.empty[K, mutable.ArrayBuffer[Node[K, T]]]
    val nodes       = mutable.HashMap.empty[K, Node[K, T]]
    values.foreach { value =>
      val key = toKey(value)
      if (rootParents.contains(key)) {
        // scalastyle:off return
        return None
        // scalastyle:on return
      } else {
        val node      = Node(key, value, mutable.ArrayBuffer.empty[Node[K, T]])
        val parentKey = toParent(value)
        rootParents.get(parentKey) match {
          case Some(children) => children.append(node)
          case None =>
            nodes.get(parentKey) match {
              case Some(parentNode) => parentNode.children.append(node)
              case None             => rootParents += (parentKey -> mutable.ArrayBuffer(node))
            }
        }
        nodes += key -> node
      }
    }

    val roots = mutable.ArrayBuffer.empty[Node[K, T]]
    rootParents.values.foreach(roots ++= _)
    Some(new Forest(roots))
  }

  def build[K, T](value: T, toKey: T => K): Forest[K, T] = {
    val node = Node(toKey(value), value, mutable.ArrayBuffer.empty[Node[K, T]])
    new Forest(mutable.ArrayBuffer(node))
  }

  final case class Node[K, T](key: K, value: T, children: mutable.ArrayBuffer[Node[K, T]]) {
    @SuppressWarnings(Array("org.wartremover.warts.Recursion"))
    def contains(another: K): Boolean = {
      key == another || children.exists(_.contains(another))
    }

    @SuppressWarnings(Array("org.wartremover.warts.Recursion"))
    def flatten: AVector[Node[K, T]] = {
      AVector(this) ++ children.foldLeft(AVector.empty[Node[K, T]])(_ ++ _.flatten)
    }
  }
}

// Note: we use ArrayBuffer instead of Set because the number of forks in blockchain is usually small
final class Forest[K, T](val roots: mutable.ArrayBuffer[Node[K, T]]) {
  def isEmpty: Boolean = roots.isEmpty

  def nonEmpty: Boolean = roots.nonEmpty

  def contains(key: K): Boolean = {
    roots.exists(_.contains(key))
  }

  def flatten: AVector[Node[K, T]] = {
    roots.foldLeft(AVector.empty[Node[K, T]])(_ ++ _.flatten)
  }

  def removeRootNode(key: K): Option[Node[K, T]] = {
    withRemove(key) { (index, node) =>
      roots.remove(index)
      roots.appendAll(node.children)
    }
  }

  def removeBranch(key: K): Option[Node[K, T]] = {
    withRemove(key) { (index, _) =>
      roots.remove(index)
      ()
    }
  }

  private def withRemove(key: K)(f: (Int, Node[K, T]) => Unit): Option[Node[K, T]] = {
    val index = roots.indexWhere(_.key == key)
    if (index == -1) None
    else {
      val node = roots(index)
      f(index, node)
      Some(node)
    }
  }

  // Note: the other forest might connected to this current forest
  def simpleMerge(another: Forest[K, T]): Unit = {
    roots.appendAll(another.roots)
  }
}
