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

import scala.{specialized => sp}
import scala.collection.mutable.ArrayBuffer
import scala.math.Ordering.Double.IeeeOrdering
import scala.reflect.ClassTag
import scala.util.Random

import org.scalacheck.{Arbitrary, Gen}
import org.scalatest.Assertion

abstract class AVectorSpec[@sp A: ClassTag](implicit ab: Arbitrary[A], cmp: Ordering[A])
    extends AlephiumSpec {

  behavior of "AVector"

  trait Fixture {
    lazy val sizeGen: Gen[Int]           = Gen.choose(4, 8)
    lazy val arrayGen: Gen[List[A]]      = Gen.nonEmptyListOf(ab.arbitrary)
    lazy val vectorGen0: Gen[AVector[A]] = arrayGen.map(as => AVector.from(as))
    lazy val vectorGen1: Gen[AVector[A]] =
      arrayGen.map(list => AVector.from(list ++ list).take(list.length))
    lazy val vectorGen2: Gen[AVector[A]] =
      arrayGen.map(list => AVector.from(list ++ list).takeRight(list.length))
    lazy val vectorGen3: Gen[AVector[A]] =
      arrayGen.map(list => AVector.from(list ++ list).drop(0).take(list.length))
    lazy val vectorGen: Gen[AVector[A]] =
      Gen.oneOf(vectorGen0, vectorGen1, vectorGen2, vectorGen3)

    def checkState[B](
        vector: AVector[B],
        start: Int,
        end: Int,
        length: Int,
        capacity: Int,
        appendable: Boolean
    ): Assertion = {
      vector.start is start
      vector.end is end
      vector.length is length
      vector.capacity is capacity
      vector.appendable is appendable
    }

    def checkState[B](vector: AVector[B], length: Int): Assertion = {
      checkState(vector, 0, length, length, length, true)
    }

    def checkEq(vc: AVector[A], xs: scala.collection.mutable.Seq[A]): Unit = {
      vc.length is xs.length
      xs.indices.foreach { i => vc(i) should be(xs(i)) }
    }
  }

  it should "create empty vector" in new Fixture {
    val vector = AVector.empty[A]
    checkState(vector, 0, 0, 0, AVector.defaultSize, true)
  }

  it should "create vector with ofSize" in new Fixture {
    forAll(sizeGen) { n =>
      val vector = AVector.ofSize[A](n)
      checkState(vector, 0, 0, 0, n, true)
    }
  }

  it should "create vector using tabulate" in new Fixture {
    forAll(sizeGen) { n =>
      val vector = AVector.tabulate[Int](n)(identity)
      checkState(vector, n)
      vector.foreachWithIndex { (elem, index) => elem is index }
    }
  }

  it should "create vector from Iterable" in new Fixture {
    forAll { xs: List[A] =>
      val vector = AVector.from(xs)
      checkState(vector, xs.length)
      xs.indices.foreach { i => vector(i) is xs(i) }
    }
  }

  it should "create vector from Array using unsafe" in new Fixture {
    forAll { arr: Array[A] =>
      val vector = AVector.unsafe(arr)
      checkState(vector, arr.length)
      vector.elems is arr
    }
  }

  it should "find the correct next pow of two" in {
    0 until 4 foreach { e =>
      val start = 1 << e
      val end   = 1 << (e + 1)
      (start + 1) until end foreach { n => AVector.nextPowerOfTwo(n) is end }
    }
  }

  it should "check empty" in {
    val vc0 = AVector.empty[A]
    vc0.isEmpty is true
    vc0.nonEmpty is false

    forAll { xs: Array[A] =>
      val vc1 = AVector.from(xs)
      vc1.isEmpty is xs.isEmpty
      vc1.nonEmpty is xs.nonEmpty
    }
  }

  it should "deconstruct" in new Fixture {
    forAll { xs: Array[A] =>
      whenever(xs.nonEmpty) {
        val vc = AVector.from(xs)
        vc.head is xs.head
        vc.headOption is xs.headOption
        vc.last is xs.last
        vc.lastOption is xs.lastOption
        checkEq(vc.init, xs.init)
        checkEq(vc.tail, xs.tail)
      }
    }
  }

  it should "chain  tail and headOption/lastOption" in new Fixture {
    val vector = AVector(1, 2, 3)
    vector.headOption is Some(1)
    vector.lastOption is Some(3)

    vector.tail is AVector(2, 3)

    vector.tail.headOption is Some(2)
    vector.tail.lastOption is Some(3)
  }

  it should "get element by index" in {
    forAll { xs: Array[A] =>
      whenever(xs.length >= 4) {
        val vc0 = AVector.unsafe(xs)
        vc0.get(-1).isEmpty is true
        vc0.get(vc0.length).isEmpty is true
        val vc1 = vc0.take(4)
        0 until 4 foreach { i =>
          vc1(i) is xs(i)
          vc1.get(i) is Some(xs(i))
        }
        val vc2 = vc0.takeRight(4)
        0 until 4 foreach { i =>
          vc2(i) is xs(xs.length - 4 + i)
          vc2.get(i) is Some(xs(xs.length - 4 + i))
        }
      }
    }
  }

  it should "grow size" in {
    val vc0 = AVector.empty[A]
    0 to 3 * AVector.defaultSize foreach { n =>
      vc0.ensureSize(n)
      if (n <= AVector.defaultSize) {
        vc0.capacity is AVector.defaultSize
      } else {
        vc0.capacity is AVector.nextPowerOfTwo(n)
      }
    }
  }

  it should "append element" in new Fixture {
    forAll(vectorGen, ab.arbitrary) { (vc: AVector[A], a: A) =>
      val vc1 = vc :+ a
      checkEq(vc1, vc.toArray :+ a)
    }
  }

  it should "insert element to the head of the vector" in new Fixture {
    forAll(vectorGen, ab.arbitrary) { (vc: AVector[A], a: A) =>
      val vc1 = a +: vc
      checkEq(vc1, a +: vc.toArray)
    }
  }

  it should "append elements" in new Fixture {
    forAll(vectorGen, vectorGen) { (vc0: AVector[A], vc1: AVector[A]) =>
      val vc = vc0 ++ vc1
      checkEq(vc, vc0.toArray ++ vc1.toArray)
    }
  }

  it should "take/drop elements" in new Fixture {
    forAll(vectorGen0) { vc =>
      vc.take(0).isEmpty is true
      vc.take(vc.length) is vc
      vc.takeUpto(vc.length + 1) is vc
      vc.takeRight(0).isEmpty is true
      vc.takeRight(vc.length) is vc
      vc.takeRightUpto(vc.length + 1) is vc
      vc.drop(0) is vc
      vc.drop(vc.length).isEmpty is true
      vc.dropUpto(vc.length + 1).isEmpty is true
      vc.dropRight(0) is vc
      vc.dropRight(vc.length).isEmpty is true
      vc.dropRightUpto(vc.length + 1).isEmpty is true

      val k = Random.nextInt(vc.length)
      checkEq(vc.take(k), vc.toArray.take(k))
      checkEq(vc.takeRight(k), vc.toArray.takeRight(k))
      checkEq(vc.drop(k), vc.toArray.drop(k))
      checkEq(vc.dropRight(k), vc.toArray.dropRight(k))
    }
  }

  it should "contain" in new Fixture {
    forAll(vectorGen, ab.arbitrary) { (vc: AVector[A], a: A) =>
      vc.contains(a) is vc.toArray.contains(a)
      vc.foreach { elem => vc.contains(elem) is true }
    }
  }

  it should "reverse vector" in new Fixture {
    forAll(vectorGen) { vc => checkEq(vc.reverse, vc.toArray.reverse) }
  }

  it should "foreach" in new Fixture {
    forAll(vectorGen) { vc =>
      val buffer = ArrayBuffer.empty[A]
      vc.foreach { elem => buffer.append(elem) }
      checkEq(vc, buffer)

      val arr = new Array[A](vc.length)
      vc.foreachWithIndex { (elem, i) => arr(i) = elem }
      checkEq(vc, arr)
      vc.foreachWithIndexE { (_, i) => Right(require(i >= 0 && i < vc.length)) } isE ()
      vc.foreachWithIndexE { (_, _) => Left(()) }.isLeft is true
    }
  }

  it should "map" in new Fixture {
    forAll(vectorGen) { vc =>
      val vc0 = vc.map(identity)
      vc0.capacity is vc.length
      checkEq(vc0, vc.toArray)

      val vc1 = vc.mapWithIndex { (elem, i) =>
        vc0(i) is vc(i)
        elem
      }
      checkEq(vc1, vc.toArray)

      val vc2 = vc.mapWithIndexE { (elem, _) =>
        Right(elem)
      }
      vc2.rightValue is vc1

      val vc3 = vc.mapWithIndexE { (_, _) =>
        Left(())
      }
      vc3.isLeft is true

      val arr = vc.mapToArray(identity)
      checkEq(vc, arr)
    }
  }

  it should "filter" in new Fixture {
    forAll(vectorGen, ab.arbitrary) { (vc, a) =>
      val arr  = vc.toArray
      val p    = cmp.lt(_: A, a)
      val vc0  = vc.filter(p)
      val arr0 = arr.filter(p)
      checkEq(vc0, arr0)
      val vc1  = vc.filterNot(p)
      val arr1 = arr.filterNot(p)
      checkEq(vc1, arr1)
    }
  }

  it should "filterE" in new Fixture {
    forAll(vectorGen, ab.arbitrary) { (vc, a) =>
      val arr  = vc.toArray
      val p    = cmp.lt(_: A, a)
      val vc0  = vc.filterE[Unit](e => Right(p(e)))
      val arr0 = arr.filter(p)
      checkEq(vc0.rightValue, arr0)
      val vc1  = vc.filterNotE[Unit](e => Right(p(e)))
      val arr1 = arr.filterNot(p)
      checkEq(vc1.rightValue, arr1)
      vc.filterE[Unit](_ => Left(())).isLeft is true
      vc.filterNotE[Unit](_ => Left(())).isLeft is true
    }
  }

  trait FixtureF extends Fixture {
    def alwaysRight: A => Either[Unit, A] = Right.apply
    def alwaysLeft: A => Either[Unit, A]  = _ => Left(())

    def doNothing: A => Either[Unit, Unit] = _ => Right(())
  }

  it should "traverse" in new FixtureF {
    forAll(vectorGen) { vc =>
      vc.mapE(alwaysRight) isE vc
      vc.mapE(alwaysLeft).isLeft is true
    }
  }

  it should "foreachE" in new FixtureF {
    forAll(vectorGen) { vc => vc.foreachE[Unit](doNothing).isRight is true }
  }

  it should "exists" in new Fixture {
    forAll(vectorGen, ab.arbitrary) { (vc, a) =>
      val arr = vc.toArray
      arr.foreach { elem => vc.exists(_ equals elem) is vc.contains(elem) }
      vc.exists(_ equals a) is vc.contains(a)
      vc.foreachWithIndex { (elem, index) =>
        vc.existsWithIndex((e, i) => (e equals elem) && (i equals index)) is true
        vc.existsWithIndex((e, i) => (e equals elem) && (i equals -1)) is false
      }
    }
  }

  it should "flatMap" in new Fixture {
    forAll(vectorGen) { vc =>
      val arr = vc.toArray
      val vc0 = vc.flatMap(AVector(_))
      checkEq(vc0, arr)
      val vc1 = vc0.flatMap(elem => AVector(elem, elem))
      checkEq(vc1, arr.flatMap(x => Array(x, x)))

      val vc2 = vc.flatMapWithIndex { case (elem, i) =>
        vc(i) is elem
        AVector(elem)
      }
      vc2 is vc

      val vc3 = vc.flatMapWithIndexE { case (elem, i) =>
        vc(i) is elem
        Right(AVector(elem))
      }
      vc3 isE vc

      val vc4 = vc.flatMapWithIndexE { case (elem, i) =>
        vc(i) is elem
        Left(())
      }
      vc4.isLeft is true
    }
  }

  it should "flatMapE" in new FixtureF {
    forAll(vectorGen) { vc =>
      val arr = vc.toArray
      val vc0 = vc.flatMapE(e => Right(AVector(e))).rightValue
      checkEq(vc0, arr)
      val vc1 = vc0.flatMapE(elem => Right(AVector(elem, elem))).rightValue
      checkEq(vc1, arr.flatMap(x => Array(x, x)))
    }
  }

  it should "find" in new Fixture {
    forAll(vectorGen) { vc =>
      val arr = vc.toArray
      arr.foreach { elem =>
        vc.find(_ equals elem) is arr.find(_ equals elem)
        vc.find(_ => false) is arr.find(_ => false)
      }
    }
  }

  it should "indexWhere" in new Fixture {
    forAll(vectorGen) { vc =>
      val arr = vc.toArray
      arr.foreach { elem =>
        vc.indexWhere(_ equals elem) is arr.indexWhere(_ equals elem)
        vc.indexWhere(_ => false) is arr.indexWhere(_ => false)
      }
    }
  }

  it should "replace" in new Fixture {
    forAll(vectorGen0.filter(_.nonEmpty)) { vc =>
      val index = Random.nextInt(vc.length)
      val vc1   = vc.replace(index, vc.head)
      vc.indices.foreach { i => if (i equals index) vc1(i) is vc.head else vc1(i) is vc(i) }
    }
  }

  it should "convert to array" in new Fixture {
    forAll(vectorGen) { vc =>
      val arr = vc.toArray
      0 until vc.length foreach { i => vc(i) is arr(i) }
    }
  }

  it should "return correct indices" in new Fixture {
    forAll(vectorGen) { vc =>
      vc.indices.length is vc.length
      vc.indices.start is 0
      vc.indices.step is 1
    }
  }

  it should "toIterable" in new Fixture {
    forAll(vectorGen) { vc =>
      vc.toIterable.toSeq is vc.toArray.toSeq
    }
  }

  it should "append empty vector efficiently" in new Fixture {
    (Array.empty[Int] eq Array.empty[Int]) is false // eq checks reference equality
    forAll(vectorGen) { vc =>
      val vc1 = vc ++ AVector.empty[A]
      vc1.toSeq is vc.toSeq
      (vc1.elems eq vc.elems) is true
    }
  }
}

class BooleanAVectorSpec extends AVectorSpec[Boolean]
class StringAVectorSpec  extends AVectorSpec[String]
class DoubleAVectorSpec  extends AVectorSpec[Double]
class IntAVectorSpec extends AVectorSpec[Int] {

  it should "create vector from array" in new Fixture {
    val vc0 = AVector(0 until AVector.defaultSize - 1: _*)
    vc0.length is AVector.defaultSize - 1
    vc0.capacity is AVector.defaultSize
    val vc1 = AVector(0 until AVector.defaultSize + 1: _*)
    vc1.length is AVector.defaultSize + 1
    vc1.capacity is AVector.defaultSize + 1
  }

  it should "withFilter" in new Fixture {
    forAll(vectorGen, Arbitrary.arbInt.arbitrary) { (vc: AVector[Int], n: Int) =>
      whenever(vc.nonEmpty) {
        val vc0 = for {
          x <- vc
          if x > n
          if x < 2 * n
          y <- AVector(x + 1, x + 2)
        } yield y
        val arr0 = for {
          x <- vc.toArray
          if x > n
          if x < 2 * n
          y <- Array(x + 1, x + 2)
        } yield y
        checkEq(vc0, arr0)
      }
    }
  }

  it should "fold / reduce / reduceBy" in new Fixture {
    forAll(vectorGen) { vc =>
      val arr = vc.toArray

      val sum0 = vc.fold(0)(_ + _)
      sum0 is arr.sum
      val sum1 = vc.fold(1)(_ + _)
      sum1 is arr.foldLeft(1)(_ + _)

      val sum2 = vc.reduce(_ + _)
      sum2 is sum0

      val sum3 = vc.reduceBy(1 - _)(_ + _)
      sum3 is arr.map(1 - _).sum
    }
  }

  it should "foldE / reduceByE" in new FixtureF {
    forAll(vectorGen) { vc =>
      vc.foldE(0)((acc, e) => Right(acc + e)) isE vc.sum
      vc.foldE(0)((_, _) => Left(())).isLeft is true

      vc.reduceByE(e => Right(e))(_ + _) isE vc.sum
      vc.reduceByE[Unit, Int](_ => Left(()))(_ + _).isLeft is true
    }
  }

  it should "foldWithIndexE" in new FixtureF {
    forAll(vectorGen) { vc =>
      val expected = vc.sum + vc.indices.sum
      vc.foldWithIndexE(0)((acc, e, idx) => Right(acc + e + idx)) isE expected
    }
  }

  it should "zipWithIndex" in new FixtureF {
    forAll(vectorGen) { vc =>
      val expected = vc.toSeq.zipWithIndex
      vc.zipWithIndex.toSeq is expected
    }
  }

  it should "collect" in new FixtureF {
    AVector(-1, 2, 3).collect { case i if i > 0 => i * i } is AVector(4, 9)
  }

  it should "forall" in new Fixture {
    AVector.empty[Int].forall(_ > 0) is true
    AVector.empty[Int].forall(_ < 0) is true
    AVector.empty[Int].forall(_ equals 0) is true
    AVector(1, 2, 3).forall(_ > 0) is true
    AVector(-1, 2, 3).forall(_ > 0) is false
    AVector(1, -2, 3).forall(_ > 0) is false
    AVector(1, 2, -3).forall(_ > 0) is false
    AVector(1, 2, 3).forallWithIndex(_ > _) is true
    AVector(1, 2, 2).forallWithIndex(_ > _) is false
  }

  it should "forallE" in new FixtureF {
    forAll(vectorGen) { vc =>
      vc.forallE(e => Right(e equals e)) isE true
      vc.forallE(e => Right(e != vc.last)) isE false
      vc.forallE(_ => Left(())).isLeft is true
    }
  }

  it should "scalaLeft" in new Fixture {
    forAll(vectorGen) { vc =>
      val arr  = vc.toArray
      val scan = vc.scanLeft(0)(_ + _)
      checkEq(scan, arr.scanLeft(0)(_ + _))
    }
  }

  it should "sum/sumBy/min/max/minBy/maxBy" in new Fixture {
    forAll(vectorGen) { vc =>
      val arr = vc.toArray
      vc.sum is arr.sum
      vc.min is arr.min
      vc.max is arr.max
      vc.sumBy(_ + 1) is arr.map(_ + 1).sum
      vc.maxBy(-_) is arr.maxBy(-_)
      vc.maxBy(_ + 1) is arr.maxBy(_ + 1)
      vc.minBy(-_) is arr.minBy(-_)
      vc.minBy(_ + 1) is arr.minBy(_ + 1)
    }
  }

  it should "splitBy" in {
    val vc0 = AVector.fill(1)(0)
    val vc1 = AVector.fill(2)(1)
    val vc2 = AVector.fill(3)(2)

    AVector.empty[Int].split() is AVector.empty[AVector[Int]]
    vc0.split() is AVector(vc0)
    vc1.split() is AVector(vc1)
    vc2.split() is AVector(vc2)
    (vc0 ++ vc1).split() is AVector(vc0, vc1)
    (vc2 ++ vc1).split() is AVector(vc2, vc1)
    (vc0 ++ vc1 ++ vc2).split() is AVector(vc0, vc1, vc2)
  }

  it should "groupBy" in {
    val vc0 = AVector(0, 1, 2)
    vc0.groupBy(identity) is Map(0 -> AVector(0), 1 -> AVector(1), 2 -> AVector(2))
    vc0.groupBy(_ => 1) is Map(1 -> AVector(0, 1, 2))
    vc0.groupBy(_ % 2) is Map(0 -> AVector(0, 2), 1 -> AVector(1))
  }

  it should "create matrix using tabulate" in new Fixture {
    forAll(sizeGen, sizeGen) { (n1, n2) =>
      val matrix = AVector.tabulate[Int](n1, n2)(_ + _)
      checkState(matrix, 0, n1, n1, n1, true)
      matrix.foreachWithIndex { (vector, index1) =>
        checkState(vector, 0, n2, n2, n2, true)
        vector.foreachWithIndex { (elem, index2) => elem is index1 + index2 }
      }
      AVector.tabulateE[Int, Unit](n1)(Right(_)) isE AVector.tabulate(n1)(identity)
      AVector
        .tabulateE[Int, Unit](n1)(k => if (k equals (n1 / 2)) Left(()) else Right(k))
        .leftValue is ()
    }
  }

  it should "fill" in new Fixture {
    val vc = AVector.fill(AVector.defaultSize)(AVector.defaultSize)
    vc.length is AVector.defaultSize
    vc.foreach(_ is AVector.defaultSize)
  }

  it should "not share the underlying array" in new Fixture {
    val vc0 = AVector(1, 2, 3)
    val vc1 = vc0.tail
    vc0.appendable is true
    vc1.appendable is false

    val vc2 = vc0 :+ 3
    val vc3 = vc1 :+ 4
    vc0.appendable is false
    vc3.appendable is true
    vc2 is AVector(1, 2, 3, 3)
    vc3 is AVector(2, 3, 4)
  }

  it should "group" in new Fixture {
    val vc0 = AVector(0, 1, 2, 3, 4, 5)
    vc0.grouped(1) is vc0.map(AVector(_))
    vc0.grouped(3) is AVector(AVector(0, 1, 2), AVector(3, 4, 5))
    assertThrows[AssertionError](vc0.grouped(4))
    assertThrows[AssertionError](vc0.grouped(5))
    assertThrows[AssertionError](vc0.grouped(100))
  }

  it should "group with remainder" in new Fixture {
    val vc0 = AVector(0, 1, 2, 3, 4, 5)
    vc0.groupedWithRemainder(1) is vc0.map(AVector(_))
    vc0.groupedWithRemainder(3) is AVector(AVector(0, 1, 2), AVector(3, 4, 5))
    vc0.groupedWithRemainder(4) is AVector(AVector(0, 1, 2, 3), AVector(4, 5))
    vc0.groupedWithRemainder(5) is AVector(AVector(0, 1, 2, 3, 4), AVector(5))
    vc0.groupedWithRemainder(100) is AVector(AVector(0, 1, 2, 3, 4, 5))
  }

  it should "withFilter (2)" in new Fixture {
    forAll(vectorGen) { vc =>
      val vc0 = for {
        a <- vc
      } yield a + 1
      vc0 is vc.map(_ + 1)

      val vc1 = for {
        a <- vc
        b <- AVector(a)
      } yield b - 1
      vc1 is vc.map(_ - 1)

      var vc2 = AVector.empty[Int]
      for {
        a <- vc
      } yield {
        vc2 = vc2 :+ a
      }
      vc2 is vc
    }
  }

  it should "sort" in new Fixture {
    forAll(vectorGen) { vc =>
      checkEq(vc.sorted, vc.toArray.sorted)
      checkEq(vc.sortBy(-_), vc.toArray.sortBy(-_))
    }
  }

  it should "update appendable" in {
    val vc0 = AVector.empty[Int]
    vc0.appendable is true
    val vc1 = vc0 :+ 1
    vc0.appendable is false
    vc1.appendable is true
    (vc1.elems eq vc0.elems) is true

    val vc2 = vc1 ++ AVector(2, 3)
    vc1.appendable is false
    vc2.appendable is true
    (vc2.elems eq vc1.elems) is true
  }
}

class SpecialAVectorSpec extends AlephiumSpec {
  it should "convert covariantly" in {
    sealed trait Foo
    final case class Bar(n: Int) extends Foo

    val vector    = AVector(1, 2, 3).map(Bar)
    val converted = vector.as[Foo]
    converted.length is 3
    converted(0).asInstanceOf[Bar] is Bar(1)
    converted(1).asInstanceOf[Bar] is Bar(2)
    converted(2).asInstanceOf[Bar] is Bar(3)
  }
}
