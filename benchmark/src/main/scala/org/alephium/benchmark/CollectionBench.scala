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
  val N = 1000000

  val vector  = Vector.tabulate(N)(identity)
  val avector = AVector.tabulate(N)(identity)

  @Benchmark
  def accessVector(): Int = {
    var sum = 0
    (0 until N).foreach { _ =>
      sum += vector(Random.nextInt(N))
    }
    sum
  }

  @Benchmark
  def accessAVector(): Int = {
    var sum = 0
    (0 until N).foreach { _ =>
      sum += avector(Random.nextInt(N))
    }
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
