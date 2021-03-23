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

import org.scalatest.Assertion

import org.alephium.util.Forest.Node

class ForestSpec extends AlephiumSpec {
  def checkBuild(roots: AVector[Int], pairs: List[(Int, Int)]): Assertion = {
    val links  = mutable.HashMap.empty ++ pairs
    val values = AVector.from(pairs.map(_._1))
    val forest = Forest.tryBuild[Int, Int](values, identity, links.apply).get
    forest.roots.map(_.key).toSet is roots.toSet

    def iter(node: Node[Int, Int]): Unit = {
      links.remove(node.key)
      node.children.foreach { child =>
        links(child.key) is node.key
        iter(child)
      }
    }
    forest.roots.foreach(iter)
    links.isEmpty is true
  }

  it should "build empty forest" in {
    checkBuild(AVector.empty[Int], List.empty)
  }

  it should "build for one value" in {
    val forest = Forest.build[Int, Int](0, _ + 1)
    forest.roots.size is 1
    forest.roots.head.key is 1
    forest.roots.head.value is 0
    forest.roots.head.children.isEmpty is true
  }

  // Paths
  val path0 = List(1 -> 0)
  val path1 = List(1 -> 0, 2 -> 1)
  val path2 = List(1 -> 0, 2 -> 1, 3 -> 2)
  val path3 = List(1 -> 0, 2 -> 1, 3 -> 2, 4 -> 3)
  // Trees
  val tree0 = List(1 -> 0, 2 -> 0)
  val tree1 = List(1 -> 0, 2 -> 0, 3 -> 1, 4 -> 1)
  val tree2 = List(1 -> 0, 2 -> 0, 3 -> 1, 4 -> 1, 5 -> 2)
  val tree3 = List(1 -> 0, 2 -> 0, 3 -> 1, 4 -> 2, 5 -> 2)
  // Forests
  val forest0 = List(2 -> 0, 3 -> 1)
  val forest1 = List(2 -> 0, 3 -> 0, 4 -> 1, 5 -> 1)
  val forest2 = List(2 -> 0, 3 -> 0, 4 -> 1, 5 -> 1, 6 -> 2, 7 -> 5)
  // All
  val tests =
    List(path0, path1, path2, path3, tree0, tree1, tree2, tree3, forest0, forest1, forest2)

  it should "build path" in {
    checkBuild(AVector(1), path0)
    checkBuild(AVector(1), path1)
    checkBuild(AVector(1), path2)
    checkBuild(AVector(1), path3)
  }

  it should "build tree" in {
    checkBuild(AVector(1, 2), tree0)
    checkBuild(AVector(1, 2), tree1)
    checkBuild(AVector(1, 2), tree2)
    checkBuild(AVector(1, 2), tree3)
  }

  it should "build forest" in {
    checkBuild(AVector(2, 3), forest0)
    checkBuild(AVector(2, 3, 4, 5), forest1)
    checkBuild(AVector(2, 3, 4, 5), forest2)
  }

  def build(pairs: List[(Int, Int)]): Option[Forest[Int, Int]] = {
    val links  = mutable.HashMap.empty ++ pairs
    val values = AVector.from(pairs.map(_._1))
    Forest.tryBuild[Int, Int](values, identity, links.apply)
  }

  def invalid(pairs: List[(Int, Int)]): Assertion = {
    build(pairs) is None
  }

  it should "not build" in {
    invalid(List(2 -> 1, 1 -> 0))
    invalid(List(1 -> 0, 4 -> 2, 2 -> 0, 3 -> 1, 5 -> 2))
    invalid(List(2 -> 0, 7 -> 5, 3 -> 0, 4 -> 1, 5 -> 1, 6 -> 2))
  }

  def checkContains(pairs: List[(Int, Int)]): Assertion = {
    val forest = build(pairs).get
    forest.contains(0) is false
    pairs.map(_._1).foreach(key => forest.contains(key) is true)
    forest.contains(-1) is false
  }

  it should "check contains" in {
    tests.foreach(checkContains)
  }

  def checkFlatten(pairs: List[(Int, Int)]): Assertion = {
    val forest = build(pairs).get
    val nodes  = forest.flatten
    nodes.map(_.key).toSet is pairs.map(_._1).toSet
    nodes.map(_.value).toSet is pairs.map(_._1).toSet
  }

  it should "check flatten" in {
    tests.foreach(checkFlatten)
  }

  def checkRemoveNode(
      pairs: List[(Int, Int)],
      key: Int,
      expected: Option[Int],
      newRootSize: Int
  ): Assertion = {
    val forest = build(pairs).get
    forest.removeRootNode(key).map(_.key) is expected
    forest.roots.size is newRootSize
  }

  it should "remove nodes" in {
    checkRemoveNode(path3, 0, None, 1)
    checkRemoveNode(path3, 1, Some(1), 1)
    checkRemoveNode(tree2, 0, None, 2)
    checkRemoveNode(tree2, 1, Some(1), 3)
    checkRemoveNode(tree2, 2, Some(2), 2)
    checkRemoveNode(forest2, 0, None, 4)
    checkRemoveNode(forest2, 1, None, 4)
    checkRemoveNode(forest2, 2, Some(2), 4)
    checkRemoveNode(forest2, 3, Some(3), 3)
  }

  def checkRemoveBranch(
      pairs: List[(Int, Int)],
      key: Int,
      expected: Option[Int],
      newRootSize: Int
  ): Assertion = {
    val forest = build(pairs).get
    forest.removeBranch(key).map(_.key) is expected
    forest.roots.size is newRootSize
  }

  it should "remove branch" in {
    checkRemoveBranch(path3, 0, None, 1)
    checkRemoveBranch(path3, 1, Some(1), 0)
    checkRemoveBranch(tree2, 0, None, 2)
    checkRemoveBranch(tree2, 1, Some(1), 1)
    checkRemoveBranch(tree2, 2, Some(2), 1)
    checkRemoveBranch(forest2, 0, None, 4)
    checkRemoveBranch(forest2, 1, None, 4)
    checkRemoveBranch(forest2, 2, Some(2), 3)
    checkRemoveBranch(forest2, 3, Some(3), 3)
  }
}
