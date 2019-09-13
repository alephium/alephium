package org.alephium.util

import scala.{specialized => sp}
import scala.collection.mutable.ArrayBuffer
import scala.reflect.ClassTag
import scala.util.Random

import org.scalacheck.{Arbitrary, Gen}
import org.scalatest.Assertion
import org.scalatest.EitherValues._

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

    def checkState[B: ClassTag](vector: AVector[B],
                                start: Int,
                                end: Int,
                                length: Int,
                                capacity: Int,
                                appendable: Boolean): Assertion = {
      vector.start is start
      vector.end is end
      vector.length is length
      vector.capacity is capacity
      vector.appendable is appendable
    }

    def checkState[B: ClassTag](vector: AVector[B], length: Int): Assertion = {
      checkState(vector, 0, length, length, length, true)
    }

    def checkEq(vc: AVector[A], xs: Seq[A]): Unit = {
      vc.length is xs.length
      xs.indices.foreach { i =>
        vc(i) should be(xs(i))
      }
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
      vector.foreachWithIndex { (elem, index) =>
        elem is index
      }
    }
  }

  it should "create vector from Iterable" in new Fixture {
    forAll { xs: List[A] =>
      val vector = AVector.from(xs)
      checkState(vector, xs.length)
      xs.indices.foreach { i =>
        vector(i) is xs(i)
      }
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
      (start + 1) until end foreach { n =>
        AVector.nextPowerOfTwo(n) is end
      }
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
        vc.last is xs.last
        checkEq(vc.init, xs.init)
        checkEq(vc.tail, xs.tail)
      }
    }
  }

  it should "get element by index" in {
    forAll { xs: Array[A] =>
      whenever(xs.length >= 4) {
        val vc0 = AVector.unsafe(xs)
        val vc1 = vc0.take(4)
        0 until 4 foreach { i =>
          vc1(i) is xs(i)
        }
        val vc2 = vc0.takeRight(4)
        0 until 4 foreach { i =>
          vc2(i) is xs(xs.length - 4 + i)
        }
      }
    }
  }

  it should "grow size" in {
    val vc0 = AVector.empty[A]
    0 to 3 * AVector.defaultSize foreach { n =>
      vc0.ensureSize(n)
      if (n <= AVector.defaultSize)
        vc0.capacity is AVector.defaultSize
      else vc0.capacity is AVector.nextPowerOfTwo(n)
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
      vc.foreach { elem =>
        vc.contains(elem) is true
      }
    }
  }

  it should "reverse vector" in new Fixture {
    forAll(vectorGen) { vc =>
      checkEq(vc.reverse, vc.toArray.reverse)
    }
  }

  it should "foreach" in new Fixture {
    forAll(vectorGen) { vc =>
      val buffer = ArrayBuffer.empty[A]
      vc.foreach { elem =>
        buffer.append(elem)
      }
      checkEq(vc, buffer)

      val arr = new Array[A](vc.length)
      vc.foreachWithIndex { (elem, i) =>
        arr(i) = elem
      }
      checkEq(vc, arr)
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

  trait FixtureF extends Fixture {
    def alwaysRight: A => Either[Unit, A] = Right.apply
    def alwaysLeft: A  => Either[Unit, A] = _ => Left(())

    def doNothing: A => Either[Unit, Unit] = _ => Right(())
  }

  it should "traverse" in new FixtureF {
    forAll(vectorGen) { vc =>
      vc.mapE(alwaysRight).right.value is vc
      vc.mapE(alwaysLeft).isLeft is true
    }
  }

  it should "foreachE" in new FixtureF {
    forAll(vectorGen) { vc =>
      vc.foreachE[Unit](doNothing).isRight is true
    }
  }

  it should "exists" in new Fixture {
    forAll(vectorGen, ab.arbitrary) { (vc, a) =>
      val arr = vc.toArray
      arr.foreach { elem =>
        vc.exists(_ equals elem) is vc.contains(elem)
      }
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
    }
  }

  it should "flatMapE" in new FixtureF {
    forAll(vectorGen) { vc =>
      val arr = vc.toArray
      val vc0 = vc.flatMapE(e => Right(AVector(e))).right.value
      checkEq(vc0, arr)
      val vc1 = vc0.flatMapE(elem => Right(AVector(elem, elem))).right.value
      checkEq(vc1, arr.flatMap(x => Array(x, x)))
    }
  }

  it should "indexWhere" in new Fixture {
    forAll(vectorGen) { vc =>
      val arr = vc.toArray
      arr.foreach { elem =>
        vc.indexWhere(_ equals elem) is arr.indexWhere(_ equals elem)
      }
    }
  }

  it should "replace" in new Fixture {
    forAll(vectorGen0.filter(_.nonEmpty)) { vc =>
      val index = Random.nextInt(vc.length)
      val vc1   = vc.replace(index, vc.head)
      vc.indices.foreach { i =>
        if (i equals index) vc1(i) is vc.head else vc1(i) is vc(i)
      }
    }
  }

  it should "convert to array" in new Fixture {
    forAll(vectorGen) { vc =>
      val arr = vc.toArray
      0 until vc.length foreach { i =>
        vc(i) is arr(i)
      }
    }
  }

  it should "return correct indices" in new Fixture {
    forAll(vectorGen) { vc =>
      vc.indices.length is vc.length
      vc.indices.start is 0
      vc.indices.step is 1
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

  it should "foldLeft" in new Fixture {
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

  it should "foldE" in new FixtureF {
    forAll(vectorGen) { vc =>
      vc.foldE(0)((acc, e) => Right(acc + e)).right.value is vc.sum
    }
  }

  it should "foldWithIndexE" in new FixtureF {
    forAll(vectorGen) { vc =>
      val expected = vc.sum + vc.indices.sum
      vc.foldWithIndexE(0)((acc, e, idx) => Right(acc + e + idx)).right.value is expected
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

  it should "create matrix using tabulate" in new Fixture {
    forAll(sizeGen, sizeGen) { (n1, n2) =>
      val matrix = AVector.tabulate[Int](n1, n2)(_ + _)
      checkState(matrix, 0, n1, n1, n1, true)
      matrix.foreachWithIndex { (vector, index1) =>
        checkState(vector, 0, n2, n2, n2, true)
        vector.foreachWithIndex { (elem, index2) =>
          elem is index1 + index2
        }
      }
    }
  }

  it should "fill" in new Fixture {
    val vc = AVector.fill(AVector.defaultSize)(AVector.defaultSize)
    vc.length is AVector.defaultSize
    vc.foreach(_ is AVector.defaultSize)
  }
}
