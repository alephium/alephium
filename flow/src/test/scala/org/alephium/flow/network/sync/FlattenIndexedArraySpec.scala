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

package org.alephium.flow.network.sync

import scala.util.Random

import org.alephium.protocol.config.GroupConfigFixture
import org.alephium.util.AlephiumSpec

class FlattenIndexedArraySpec extends AlephiumSpec with GroupConfigFixture.Default {

  trait Fixture {
    groups is 3

    val chainIndexes = groupConfig.cliqueChainIndexes.shuffle()

    def emptyArray: FlattenIndexedArray[Int] = {
      val array = FlattenIndexedArray.empty[Int]
      array.array.length is groupConfig.chainNum
      array
    }
  }

  it should "get and update" in new Fixture {
    val array = emptyArray
    chainIndexes.foreach(array(_) is None)
    chainIndexes.foreach(index => array(index) = index.flattenIndex)
    chainIndexes.foreach(index => array(index) is Some(index.flattenIndex))
    groupConfig.cliqueChainIndexes.foreach(index => array(index) is Some(index.flattenIndex))
  }

  it should "reset" in new Fixture {
    val array = emptyArray
    array.array.count(_.isDefined) is 0
    chainIndexes.foreach(index => array(index) = index.flattenIndex)
    array.array.count(_.isDefined) is groupConfig.chainNum

    array.reset()
    array.array.count(_.isDefined) is 0
  }

  it should "foreach" in new Fixture {
    val array = emptyArray
    var sum   = 0
    array.foreach(v => sum += v)
    sum is 0

    chainIndexes.foreach(array(_) = 1)
    array.foreach(v => sum += v)
    sum is groupConfig.chainNum
  }

  it should "forall" in new Fixture {
    val array = emptyArray
    array.forall(_ > 0) is true
    array.forall(_ < 0) is true
    array(chainIndexes.head) = -1
    array.forall(_ > 0) is false
    array.forall(_ < 0) is true

    chainIndexes.tail.foreach(array(_) = Random.nextInt())
    array.forall(_ > 0) is false
    array(chainIndexes.last) = Random.nextInt(Int.MaxValue)
    array.forall(_ < 0) is false

    array.reset()
    chainIndexes.foreach(array(_) = Random.nextInt(Int.MaxValue))
    array.forall(_ >= 0) is true
  }

  it should "exists" in new Fixture {
    val array = emptyArray
    array.exists(_ > 0) is false
    array.exists(_ < 0) is false
    array(chainIndexes.head) = -1
    array.exists(_ > 0) is false
    array.exists(_ < 0) is true

    chainIndexes.tail.foreach(array(_) = Random.nextInt())
    array.exists(_ < 0) is true

    array.reset()
    array(chainIndexes.head) = 1
    array.exists(_ < 0) is false
    array.exists(_ >= 0) is true
    chainIndexes.tail.foreach(array(_) = Random.nextInt(Int.MaxValue))
    array.exists(_ < 0) is false
    array.exists(_ >= 0) is true
  }

  it should "isEmpty" in new Fixture {
    val array = emptyArray
    array.isEmpty is true
    array.nonEmpty is false

    chainIndexes.foreach { chainIndex =>
      array(chainIndex) = 1
      array.isEmpty is false
      array.nonEmpty is true
    }
  }
}
