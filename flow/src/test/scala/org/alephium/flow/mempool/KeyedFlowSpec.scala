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
import scala.language.implicitConversions
import scala.util.Random

import org.alephium.util.{AlephiumSpec, AVector, ConcurrentHashMap, SimpleMap}

class KeyedFlowSpec extends AlephiumSpec {
  import KeyedFlowSpec._

  trait PathFixture {
    val N    = 100
    val flow = Flow(ConcurrentHashMap.empty[K, N], ConcurrentHashMap.empty, _ => 0)
    flow.isEmpty is true

    Random.shuffle(Seq.tabulate(N)(identity)).foreach { n =>
      val node = flow.newNode(
        n,
        Seq(n - 1).filter(flow.allNodes.contains(_)),
        Seq(n + 1).filter(flow.allNodes.contains(_))
      )
      flow.addNewNode(node)
    }
    val nodes = Seq.tabulate(N)(flow.allNodes.unsafe(_))
  }

  behavior of "Path"

  it should "test `addNewNode`" in new PathFixture {
    flow.size is N
    flow.isEmpty is false
    flow.checkSources(0)
    (0 until N).foreach { n =>
      flow.contains(n) is true
      val node = flow.allNodes.unsafe(n)
      node.value is n
      if (n > 0) {
        node._parents.value.map(_.value).toSeq is Seq(n - 1)
      }
      if (n < N - 1) {
        node._children.value.map(_.value).toSeq is Seq(n + 1)
      }
    }
  }

  it should "test `removeNodeAndAncestors`" in new PathFixture {
    flow.removeNodeAndAncestors(N - 1, _ => ())
    flow.isEmpty is true
  }

  it should "test `removeNodeAndDescendants`" in new PathFixture {
    flow.removeNodeAndDescendants(0, _ => ())
    flow.isEmpty is true
  }

  trait GridFixture {
    val N = 100
    val M = 4
    val flow =
      Flow(AVector.fill(M)(ConcurrentHashMap.empty[K, N]), ConcurrentHashMap.empty, n => n / N)
    flow.isEmpty is true

    Random.shuffle(Seq.tabulate(N)(identity)).foreach { n =>
      Random.shuffle(Seq.tabulate(M)(identity)).foreach { m =>
        val node = flow.newNode(
          m * N + n,
          if (n > 0) {
            Seq.tabulate(M)(_ * N + n - 1).filter(flow.allNodes.contains(_))
          } else {
            Seq.empty
          },
          if (n < N - 1) {
            Seq.tabulate(M)(_ * N + n + 1).filter(flow.allNodes.contains(_))
          } else {
            Seq.empty
          }
        )
        flow.addNewNode(node)
      }
    }

    val nodess = (0 until N).map(n => (0 until M).map(m => flow.allNodes.unsafe(m * N + n)))
  }

  behavior of "Grid"

  it should "test `addNewNode`" in new GridFixture {
    flow.size is (N * M)
    flow.isEmpty is false
    (1 until N * M).foreach(k => flow.contains(k) is true)
    flow.checkSourcess(Seq.tabulate(M)(k => Seq(k * N)): _*)
    nodess.zipWithIndex.foreach { case (nodes, n) =>
      nodes.map(_.value) is IndexedSeq.tabulate(M)(_ * N + n)
      if (n > 0) {
        nodes.foreach { node =>
          val parents = node._parents.value.map(_.value).toSet
          parents.size is M
          parents is Set.tabulate(M)(_ * N + n - 1)
        }
      }
      if (n < N - 1) {
        nodes.foreach { node =>
          val children = node._children.value.map(_.value).toSet
          children.size is M
          children is Set.tabulate(M)(_ * N + n + 1)
        }
      }
    }
  }

  it should "test `takeSourceNodes`" in new GridFixture {
    (1 until M).foreach { g =>
      val buffer = mutable.ArrayBuffer.empty[N]
      flow.takeSourceNodes(g, Int.MaxValue, buffer.addOne)
      buffer.length is 1
      buffer.head.value is (g * N)
    }
  }

  it should "test `removeNodeAndAncestors`" in new GridFixture {
    flow.removeNodeAndAncestors(N - 1, _ => ())
    flow.isEmpty is false
    val sources = (0 until M).map(k => if (k == 0) Seq.empty else Seq(k * N + N - 1))
    flow.checkSourcess(sources: _*)
    flow.allNodes.keys().map(_.value).toSet is sources.toSet.flatten
    flow.allNodes.values().foreach(_.isSource() is true)
    flow.allNodes.values().foreach(_.isSink() is true)
  }

  it should "test `removeNodeAndDescendants`" in new GridFixture {
    flow.removeNodeAndDescendants(0, _ => ())
    flow.isEmpty is false
    val sources = (0 until M).map(k => if (k == 0) Seq.empty else Seq(k * N))
    flow.checkSourcess(sources: _*)
    flow.allNodes.keys().map(_.value).toSet is sources.toSet.flatten
    flow.allNodes.values().foreach(_.isSource() is true)
    flow.allNodes.values().foreach(_.isSink() is true)
  }

  behavior of "Random Graph"

  trait GraphFixture {
    val N = 10000
    val M = 5
    val _flow =
      Flow(AVector.fill(M)(ConcurrentHashMap.empty[K, N]), ConcurrentHashMap.empty[K, N], _ % M)
    _flow.isEmpty is true

    (0 until N).foreach { n =>
      if (n == 0 || Random.nextFloat() < 0.1) {
        _flow.addNewNode(_flow.newNode(n, Seq.empty))
      } else {
        val numParent = Random.between(1, Math.min(_flow.allNodes.size + 1, 5))
        val parents   = Set.fill(numParent)(Random.nextInt(n))
        _flow.addNewNode(_flow.newNode(n, parents.toSeq))
      }
    }
    val flow =
      Flow(AVector.fill(M)(ConcurrentHashMap.empty[K, N]), ConcurrentHashMap.empty[K, N], _ % M)
    Random.shuffle(_flow.allNodes.values()).foreach { node =>
      val newNode = flow
        .newNode(
          node.value,
          node._parents
            .map(_.filter(p => flow.allNodes.contains(p.value)).map(_.value).toSeq)
            .getOrElse(Seq.empty),
          node._children
            .map(_.filter(p => flow.allNodes.contains(p.value)).map(_.value).toSeq)
            .getOrElse(Seq.empty)
        )
      flow.addNewNode(newNode)
    }
  }

  it should "test `addNewNode`" in new GraphFixture {
    flow.size is N
    flow.checkEdges()
    (0 until N).foreach(k => flow.contains(k) is true)
  }

  it should "test `removeSourceNode`" in new GraphFixture {
    flow.allNodes.size is N
    val sourceSize = flow.sourceNodes.sumBy(_.size)
    flow.sourceNodes
      .map(_.keys().toSeq) // fix all of the source nodes
      .foreach(_.foreach { key =>
        flow.removeSourceNode(key)
      })
    flow.allNodes.size is (N - sourceSize)
    flow.checkEdges()
  }

  it should "test `removeNodeAndAncestors`" in new GraphFixture {
    while (!flow.isEmpty) {
      val node = flow.allNodes.values().next() // a random node
      flow.removeNodeAndAncestors(node, _ => ())
      flow.checkEdges()
    }
  }

  it should "test `removeNodeAndDescendants`" in new GraphFixture {
    while (!flow.isEmpty) {
      val node = flow.allNodes.values().next() // a random node
      flow.removeNodeAndDescendants(node, _ => ())
      flow.checkEdges()
    }
  }

  it should "test `clear`" in new GraphFixture {
    flow.clear()
    flow.sourceNodes.foreach(_.isEmpty is true)
    flow.allNodes.isEmpty is true
  }
}

object KeyedFlowSpec extends AlephiumSpec {
  final case class K(value: Int)
  final case class N(
      value: Int,
      group: Int,
      override var _parents: Option[mutable.ArrayBuffer[N]],
      override var _children: Option[mutable.ArrayBuffer[N]]
  ) extends KeyedFlow.Node[K, N] {
    def key: K          = K(value)
    def getGroup(): Int = group
  }
  implicit def int2K(value: Int): K = K(value)

  final case class Flow(
      sourceNodes: AVector[ConcurrentHashMap[K, N]],
      allNodes: ConcurrentHashMap[K, N],
      getGroup: Int => Int
  ) extends KeyedFlow(sourceNodes.as[SimpleMap[K, N]], allNodes) {
    def newNode(value: Int, parents: Seq[Int], children: Seq[Int] = Seq.empty): N = {
      N(
        value,
        getGroup(value),
        if (parents.isEmpty) {
          None
        } else {
          Some(mutable.ArrayBuffer.from(parents.map(allNodes.unsafe(_))))
        },
        if (children.isEmpty) {
          None
        } else {
          Some(mutable.ArrayBuffer.from(children.map(allNodes.unsafe(_))))
        }
      )
    }

    def isEmpty: Boolean = allNodes.isEmpty

    def checkSources(expected: Int*): Unit = {
      checkSourcess(expected)
    }

    def checkSourcess(expected: Seq[Int]*): Unit = {
      sourceNodes.length is expected.length
      sourceNodes.toSeq.zip(expected).foreach { case (nodes, expected) =>
        nodes.keys().map(_.value).toSet is expected.toSet
      }
    }

    def checkEdges() = {
      allNodes.keys().zip(allNodes.values()).foreach { case (key, node) =>
        node.key is key
        node
          .getChildren()
          .foreach(_.foreach(child => child._parents.exists(_.exists(_ eq node))))
        node
          .getParents()
          .foreach(_.foreach(parent => parent._children.exists(_.exists(_ eq node))))
      }
    }
  }

  object Flow {
    def apply(
        sourceNodes: ConcurrentHashMap[K, N],
        allNodes: ConcurrentHashMap[K, N],
        getGroup: Int => Int
    ): Flow = {
      Flow(AVector(sourceNodes), allNodes, getGroup)
    }
  }
}
