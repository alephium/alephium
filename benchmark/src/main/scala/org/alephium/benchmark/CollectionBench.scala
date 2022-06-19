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

package org.alephium.benchmark

import java.util.concurrent.TimeUnit

import scala.util.Random

import org.openjdk.jmh.annotations._

import org.alephium.util.AVector

@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Thread)
// scalastyle:off magic.number
class CollectionBench {
  val N: Int = 1000000

  val vector: Vector[Int]   = Vector.tabulate(N)(identity)
  val avector: AVector[Int] = AVector.tabulate(N)(identity)

  @Benchmark
  def accessVector(): Int = {
    var sum = 0
    (0 until N).foreach { _ => sum += vector(Random.nextInt(N)) }
    sum
  }

  @Benchmark
  def accessAVector(): Int = {
    var sum = 0
    (0 until N).foreach { _ => sum += avector(Random.nextInt(N)) }
    sum
  }

  @Benchmark
  def appendVector(): Int = {
    val vc: Vector[Int] = (0 until N).foldLeft(Vector.empty[Int])(_ :+ _)
    vc.length
  }

  @Benchmark
  def appendAVector(): Int = {
    val vc: AVector[Int] = (0 until N).foldLeft(AVector.empty[Int])(_ :+ _)
    vc.length
  }

  @Benchmark
  def mapVector(): Int = {
    val vc: Vector[Int] = vector.map(_ + 1)
    vc.length
  }

  @Benchmark
  def mapAVector(): Int = {
    val vc: AVector[Int] = avector.map(_ + 1)
    vc.length
  }

  @Benchmark
  @SuppressWarnings(Array("org.wartremover.warts.IterableOps"))
  def filterVector(): Int = {
    val vc: Vector[Int] = vector.filter(_ % 2 == 0)
    vc.last
  }

  @Benchmark
  def filterAVector(): Int = {
    val vc: AVector[Int] = avector.filter(_ % 2 == 0)
    vc.last
  }

  @Benchmark
  def flatMapVector(): Int = {
    val vc: Vector[Int] = vector.flatMap(x => Vector(x + 1, x + 2))
    vc.length
  }

  @Benchmark
  def flatMapAVector(): Int = {
    val vc: AVector[Int] = avector.flatMap(x => AVector(x + 1, x + 2))
    vc.length
  }
}
