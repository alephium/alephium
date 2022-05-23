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
import scala.collection.immutable.ArraySeq
import scala.reflect.ClassTag
import scala.runtime.Statics
import scala.util.Random

import org.alephium.macros.HPC

/*
 * Immutable vector that is optimized for appending, not synchronized
 */
// scalastyle:off number.of.methods return
@SuppressWarnings(Array("org.wartremover.warts.While"))
abstract class AVector[@sp A](implicit val ct: ClassTag[A]) extends Serializable { self =>
  import HPC.cfor

  protected[util] var elems: Array[A]

  def capacity: Int = elems.length

  // The left index boundary (inclusive) of elements
  def start: Int

  // The right index boundary (exclusive) of elements
  def end: Int

  def length: Int = end - start

  var appendable: Boolean

  def isEmpty: Boolean = length == 0

  def nonEmpty: Boolean = length > 0

  def head: A = {
    assume(nonEmpty)
    elems(start)
  }

  def headOption: Option[A] = {
    Option.when(nonEmpty)(elems(start))
  }

  def last: A = {
    assume(nonEmpty)
    elems(end - 1)
  }

  def lastOption: Option[A] = {
    Option.when(nonEmpty)(elems(end - 1))
  }

  def init: AVector[A] = {
    assume(nonEmpty)
    AVector.unsafe(elems, start, end - 1, false)
  }

  def tail: AVector[A] = {
    assume(nonEmpty)
    AVector.unsafe(elems, start + 1, end, false)
  }

  @inline
  def apply(i: Int): A = {
    elems(start + i)
  }

  def get(i: Int): Option[A] = {
    if (i >= 0 && i < length) Some(elems(start + i)) else None
  }

  private[util] def ensureSize(n: Int): Unit = {
    assume(n >= 0)

    val goal = start + n
    if (goal > capacity) {
      val size =
        if (goal <= AVector.defaultSize) {
          AVector.defaultSize
        } else {
          AVector.nextPowerOfTwo(goal)
        }
      growTo(size)
    }
  }

  private[util] def growTo(size: Int): Unit = {
    val arr = new Array[A](size)
    System.arraycopy(elems, 0, arr, 0, capacity)
    elems = arr
  }

  def :+[B <: A](elem: B): AVector[A] = {
    if (appendable) {
      ensureSize(length + 1)
      elems(end) = elem
      appendable = false
      AVector.unsafe(elems, start, end + 1, true)
    } else {
      val arr = new Array[A](length + 1)
      System.arraycopy(elems, start, arr, 0, length)
      arr(length) = elem
      AVector.unsafe(arr)
    }
  }

  def +:(elem: A): AVector[A] = {
    val arr = new Array[A](length + 1)
    arr(0) = elem
    System.arraycopy(elems, start, arr, 1, length)
    AVector.unsafe(arr)
  }

  def ++[B <: A](that: AVector[B]): AVector[A] = {
    if (that.isEmpty) {
      this
    } else {
      val newLength = length + that.length
      if (appendable) {
        ensureSize(newLength)
        System.arraycopy(that.elems, that.start, elems, end, that.length)
        appendable = false
        AVector.unsafe(elems, start, start + newLength, true)
      } else {
        val arr = new Array[A](newLength)
        System.arraycopy(elems, start, arr, 0, length)
        System.arraycopy(that.elems, that.start, arr, length, that.length)
        AVector.unsafe(arr)
      }
    }
  }

  def contains(elem: A): Boolean = {
    cfor(start)(_ < end, _ + 1) { i => if (elems(i) == elem) return true }
    false
  }

  def exists(f: A => Boolean): Boolean = {
    cfor(start)(_ < end, _ + 1) { i =>
      val a = elems(i)
      if (f(a)) { return true }
    }
    false
  }

  def existsWithIndex(f: (A, Int) => Boolean): Boolean = {
    cfor(0)(_ < length, _ + 1) { i =>
      val a = apply(i)
      if (f(a, i)) { return true }
    }
    false
  }

  def grouped(k: Int): AVector[AVector[A]] = {
    assume(length % k == 0)
    AVector.tabulate(length / k) { l => slice(k * l, k * (l + 1)) }
  }

  def groupedWithRemainder(k: Int): AVector[AVector[A]] = {
    val remainder = length % k
    val tabulated = AVector.tabulate(length / k) { l => slice(k * l, k * (l + 1)) }
    if (remainder == 0) {
      tabulated
    } else {
      tabulated :+ takeRight(remainder)
    }
  }

  def forall(f: A => Boolean): Boolean = {
    cfor(start)(_ < end, _ + 1) { i =>
      val a = elems(i)
      if (!f(a)) { return false }
    }
    true
  }

  def forallE[L](f: A => Either[L, Boolean]): Either[L, Boolean] = {
    cfor(start)(_ < end, _ + 1) { i =>
      val a = elems(i)
      f(a) match {
        case Left(l)      => return Left(l)
        case Right(false) => return Right(false)
        case Right(true)  => ()
      }
    }
    Right(true)
  }

  def forallWithIndex(f: (A, Int) => Boolean): Boolean = {
    cfor(0)(_ < length, _ + 1) { i =>
      val a = apply(i)
      if (!f(a, i)) { return false }
    }
    true
  }

  def slice(from: Int, until: Int): AVector[A] = {
    assume(from >= 0 && from <= until && until <= length)

    AVector.unsafe(elems, start + from, start + until, false)
  }

  def take(n: Int): AVector[A] = {
    slice(0, n)
  }

  def takeUpto(n: Int): AVector[A] = {
    val m = math.min(n, length)
    slice(0, m)
  }

  def drop(n: Int): AVector[A] = {
    slice(n, length)
  }

  def dropUpto(n: Int): AVector[A] = {
    val m = math.min(n, length)
    slice(m, length)
  }

  def takeRight(n: Int): AVector[A] = {
    slice(length - n, length)
  }

  def takeRightUpto(n: Int): AVector[A] = {
    val m = math.min(n, length)
    slice(length - m, length)
  }

  def dropRight(n: Int): AVector[A] = {
    slice(0, length - n)
  }

  def dropRightUpto(n: Int): AVector[A] = {
    val m = math.min(n, length)
    slice(0, length - m)
  }

  def reverse: AVector[A] = {
    if (length < 2) this else _reverse
  }

  @inline
  private def _reverse: AVector[A] = {
    val arr       = new Array[A](length)
    val rightmost = end - 1
    cfor(0)(_ < length, _ + 1) { i => arr(i) = elems(rightmost - i) }
    AVector.unsafe(arr)
  }

  def foreach[U](f: A => U): Unit = {
    cfor(start)(_ < end, _ + 1) { i =>
      f(elems(i))
      ()
    }
  }

  def foreachWithIndex[U](f: (A, Int) => U): Unit = {
    cfor(0)(_ < length, _ + 1) { i =>
      f(apply(i), i)
      ()
    }
  }

  def map[@sp B: ClassTag](f: A => B): AVector[B] = {
    AVector.tabulate(length) { i => f(apply(i)) }
  }

  def mapE[L, R: ClassTag](f: A => Either[L, R]): Either[L, AVector[R]] = {
    val array = Array.ofDim[R](length)
    cfor(0)(_ < length, _ + 1) { i =>
      f(apply(i)) match {
        case Left(l)  => return Left(l)
        case Right(r) => array(i) = r
      }
    }
    Right(AVector.unsafe(array))
  }

  def mapToArray[@sp B: ClassTag](f: A => B): Array[B] = {
    Array.tabulate(length) { i => f(apply(i)) }
  }

  def mapWithIndex[@sp B: ClassTag](f: (A, Int) => B): AVector[B] = {
    AVector.tabulate(length) { i => f(apply(i), i) }
  }

  def mapWithIndexE[L, B: ClassTag](f: (A, Int) => Either[L, B]): Either[L, AVector[B]] = {
    val array = Array.ofDim[B](length)
    cfor(0)(_ < length, _ + 1) { i =>
      f(apply(i), i) match {
        case Left(l)  => return Left(l)
        case Right(r) => array(i) = r
      }
    }
    Right(AVector.unsafe(array))
  }

  def zipWithIndex: AVector[(A, Int)] = {
    AVector.tabulate(length) { i => (apply(i), i) }
  }

  def filter(p: A => Boolean): AVector[A] = {
    filterImpl(p, true)
  }

  def filterNot(p: A => Boolean): AVector[A] = {
    filterImpl(p, false)
  }

  def filterE[L](p: A => Either[L, Boolean]): Either[L, AVector[A]] = {
    filterEImpl(p, true)
  }

  def filterNotE[L](p: A => Either[L, Boolean]): Either[L, AVector[A]] = {
    filterEImpl(p, false)
  }

  @inline
  private def filterImpl(p: A => Boolean, target: Boolean): AVector[A] = {
    fold(AVector.empty[A]) { (acc, elem) => if (p(elem) == target) acc :+ elem else acc }
  }

  @inline
  private def filterEImpl[L](p: A => Either[L, Boolean], target: Boolean): Either[L, AVector[A]] = {
    var acc = AVector.empty[A]
    cfor(start)(_ < end, _ + 1) { i =>
      val a = elems(i)
      p(a) match {
        case Left(l)                 => return Left(l)
        case Right(t) if t == target => acc = acc :+ a
        case Right(_)                => ()
      }
    }
    Right(acc)
  }

  def foreachE[L](f: A => Either[L, Unit]): Either[L, Unit] = {
    cfor(start)(_ < end, _ + 1) { i =>
      val a = elems(i)
      f(a) match {
        case Left(l)  => return Left(l)
        case Right(_) => ()
      }
    }
    Right(())
  }

  def foreachWithIndexE[L](f: (A, Int) => Either[L, Unit]): Either[L, Unit] = {
    cfor(0)(_ < length, _ + 1) { i =>
      val a = apply(i)
      f(a, i) match {
        case Left(l)  => return Left(l)
        case Right(_) => ()
      }
    }
    Right(())
  }

  def withFilter(p: A => Boolean): WithFilter = new WithFilter(p)

  class WithFilter(p: A => Boolean) {
    def map[@sp B: ClassTag](f: A => B): AVector[B] = {
      fold(AVector.empty[B]) { (acc, elem) => if (p(elem)) acc :+ f(elem) else acc }
    }

    def flatMap[@sp B: ClassTag](f: A => AVector[B]): AVector[B] = {
      fold(AVector.empty[B]) { (acc, elem) => if (p(elem)) acc ++ f(elem) else acc }
    }

    def foreach[U](f: A => U): Unit = {
      self.foreach { elem => if (p(elem)) f(elem) }
    }

    def withFilter(q: A => Boolean): WithFilter = new WithFilter(elem => p(elem) && q(elem))
  }

  def fold[B](zero: B)(f: (B, A) => B): B = {
    var res = zero
    cfor(start)(_ < end, _ + 1) { i => res = f(res, elems(i)) }
    res
  }

  def foldWithIndex[B](zero: B)(f: (B, A, Int) => B): B = {
    var res = zero
    cfor(0)(_ < length, _ + 1) { i =>
      res = f(res, apply(i), i)
    }
    res
  }

  def foldE[L, R](zero: R)(f: (R, A) => Either[L, R]): Either[L, R] = {
    var res = zero
    cfor(start)(_ < end, _ + 1) { i =>
      val a = elems(i)
      f(res, a) match {
        case Left(l)  => return Left(l)
        case Right(r) => res = r
      }
    }
    Right(res)
  }

  def foldWithIndexE[L, R](zero: R)(f: (R, A, Int) => Either[L, R]): Either[L, R] = {
    var res = zero
    cfor(0)(_ < length, _ + 1) { i =>
      val a = apply(i)
      f(res, a, i) match {
        case Left(l)  => return Left(l)
        case Right(r) => res = r
      }
    }
    Right(res)
  }

  @SuppressWarnings(Array("org.wartremover.warts.AsInstanceOf"))
  def collect[B: ClassTag](pf: PartialFunction[A, B]): AVector[B] = {
    val marker = Statics.pfMarker
    fold(AVector.empty[B]) { case (acc, elem) =>
      val v = pf.applyOrElse(elem, ((_: A) => marker).asInstanceOf[A => B])
      if (marker ne v.asInstanceOf[AnyRef]) {
        acc :+ v
      } else {
        acc
      }
    }
  }

  def reduce(op: (A, A) => A): A = {
    reduceBy(identity)(op)
  }

  def reduceBy[@sp B](f: A => B)(op: (B, B) => B): B = {
    assume(nonEmpty)

    var acc = f(elems(start))
    cfor(start + 1)(_ < end, _ + 1) { i => acc = op(acc, f(elems(i))) }
    acc
  }

  def reduceByE[L, B](f: A => Either[L, B])(op: (B, B) => B): Either[L, B] = {
    assume(nonEmpty)

    var acc = f(elems(start)) match {
      case Right(b) => b
      case Left(l)  => return Left(l)
    }
    cfor(start + 1)(_ < end, _ + 1) { i =>
      f(elems(i)) match {
        case Right(b) => acc = op(acc, b)
        case Left(l)  => return Left(l)
      }
    }
    Right(acc)
  }

  def flatMap[@sp B: ClassTag](f: A => AVector[B]): AVector[B] = {
    fold(AVector.empty[B]) { (acc, elem) => acc ++ f(elem) }
  }

  def flatMapE[L, R: ClassTag](f: A => Either[L, AVector[R]]): Either[L, AVector[R]] = {
    foldE(AVector.empty[R]) { (acc, elem) => f(elem).map(acc ++ _) }
  }

  def flatMapWithIndex[@sp B: ClassTag](f: (A, Int) => AVector[B]): AVector[B] = {
    var res = AVector.empty[B]
    cfor(0)(_ < length, _ + 1) { i =>
      val a = apply(i)
      res = res ++ f(a, i)
    }
    res
  }

  def flatMapWithIndexE[L, R: ClassTag](
      f: (A, Int) => Either[L, AVector[R]]
  ): Either[L, AVector[R]] = {
    var res = AVector.empty[R]
    cfor(0)(_ < length, _ + 1) { i =>
      val a = apply(i)
      f(a, i) match {
        case Left(l)   => return Left(l)
        case Right(as) => res = res ++ as
      }
    }
    Right(res)
  }

  def scanLeft[@sp B: ClassTag](zero: B)(op: (B, A) => B): AVector[B] = {
    val arr = new Array[B](length + 1)
    var acc = zero
    arr(0) = acc
    cfor(0)(_ < length, _ + 1) { i =>
      acc = op(acc, apply(i))
      arr(i + 1) = acc
    }
    AVector.unsafe(arr)
  }

  def find(f: A => Boolean): Option[A] = {
    cfor(start)(_ < end, _ + 1) { i =>
      val elem = elems(i)
      if (f(elem)) return Some(elem)
    }
    None
  }

  def indexWhere(f: A => Boolean): Int = {
    cfor(start)(_ < end, _ + 1) { i => if (f(elems(i))) return i - start }
    -1
  }

  def sorted(implicit ord: Ordering[A]): AVector[A] = {
    val arr = toArray
    scala.util.Sorting.quickSort(arr)
    AVector.unsafe(arr)
  }

  def sortBy[B](f: A => B)(implicit ord: Ordering[B]): AVector[A] = {
    val arr = toArray
    scala.util.Sorting.quickSort(arr)(ord.on(f))
    AVector.unsafe(arr)
  }

  def sum(implicit num: Numeric[A]): A = fold(num.zero)(num.plus)

  def sumBy[B](f: A => B)(implicit num: Numeric[B]): B = {
    fold(num.zero) { (sum, elem) => num.plus(sum, f(elem)) }
  }

  def max(implicit cmp: Ordering[A]): A = {
    assume(nonEmpty)

    reduce((x, y) => if (cmp.gteq(x, y)) x else y)
  }

  def min(implicit cmp: Ordering[A]): A = {
    assume(nonEmpty)

    reduce((x, y) => if (cmp.lteq(x, y)) x else y)
  }

  def maxBy[B](f: A => B)(implicit cmp: Ordering[B]): A = {
    assume(nonEmpty)

    var maxA = head
    var maxB = f(maxA)
    cfor(start + 1)(_ < end, _ + 1) { i =>
      val a = elems(i)
      val b = f(a)
      if (cmp.gt(b, maxB)) {
        maxA = a
        maxB = b
      }
    }
    maxA
  }

  def minBy[B](f: A => B)(implicit cmp: Ordering[B]): A = {
    assume(nonEmpty)

    var minA = head
    var minB = f(minA)
    cfor(start + 1)(_ < end, _ + 1) { i =>
      val a = elems(i)
      val b = f(a)
      if (cmp.lt(b, minB)) {
        minA = a
        minB = b
      }
    }
    minA
  }

  def split(): AVector[AVector[A]] = {
    splitBy(identity)
  }

  def splitBy[B](f: A => B): AVector[AVector[A]] = {
    if (isEmpty) {
      AVector.empty
    } else {
      var prev = f(head)
      var acc  = AVector.empty[A]
      var res  = AVector.empty[AVector[A]]
      foreach { elem =>
        val current = f(elem)
        if (current == prev) {
          acc = acc :+ elem
        } else {
          res = res :+ acc
          acc = AVector(elem)
          prev = current
        }
      }
      res :+ acc
    }
  }

  def groupBy[K](f: A => K): Map[K, AVector[A]] = {
    fold(Map.empty[K, AVector[A]]) { case (acc, elem) =>
      val key = f(elem)
      acc.get(key) match {
        case Some(values) => acc + (key -> (values :+ elem))
        case None         => acc + (key -> AVector(elem))
      }
    }
  }

  def replace(i: Int, a: A): AVector[A] = {
    assume(i >= 0 && i < length)
    val arr = Array.ofDim[A](length)
    System.arraycopy(elems, start, arr, 0, length)
    arr(i) = a
    AVector.unsafe(arr)
  }

  def sample(): A = {
    assume(nonEmpty)
    val selected = Random.nextInt(length)
    apply(selected)
  }

  def sampleWithIndex(): (Int, A) = {
    assume(nonEmpty)
    val selected = Random.nextInt(length)
    (selected, apply(selected))
  }

  def toArray: Array[A] = {
    val arr = new Array[A](length)
    System.arraycopy(elems, start, arr, 0, length)
    arr
  }

  def toSeq: Seq[A] = {
    ArraySeq.unsafeWrapArray(toArray)
  }

  def toIterable: Iterable[A] = {
    new Iterable[A] {
      override def size: Int                   = length
      def iterator: Iterator[A]                = elems.iterator.slice(start, end)
      override def foreach[U](f: A => U): Unit = self.foreach(f)
    }
  }

  def toSet: Set[A] = {
    toIterable.toSet
  }

  def indices: Range = 0 until length

  def mkString(start: String, sep: String, end: String): String = {
    toIterable.mkString(start, sep, end)
  }

  def mkString(sep: String): String = mkString("", sep, "")

  override def equals(obj: Any): Boolean =
    obj match {
      case that: AVector[_] =>
        if (length == that.length && ct == that.ct) {
          cfor(0)(_ < length, _ + 1) { i => if (apply(i) != that(i)) return false }
          true
        } else {
          false
        }
      case _ => false
    }

  // scalastyle:off magic.number
  override def hashCode(): Int = {
    var code: Int = 0xd55d283e
    cfor(start)(_ < end, _ + 1) { i => code = (code * 19) + elems(i).## }
    code
  }

  override def toString: String = {
    toIterable.toString()
  }

  @SuppressWarnings(Array("org.wartremover.warts.AsInstanceOf"))
  def as[@sp T >: A: ClassTag]: AVector[T] = {
    AVector.unsafe(elems.asInstanceOf[Array[T]], start, end, false)
  }

  @SuppressWarnings(Array("org.wartremover.warts.AsInstanceOf"))
  def asUnsafe[T <: A: ClassTag]: AVector[T] = {
    AVector.unsafe(elems.asInstanceOf[Array[T]], start, end, false)
  }
}
// scalastyle:on

@SuppressWarnings(Array("org.wartremover.warts.While"))
object AVector {
  import HPC.cfor

  private[util] val defaultSize = 8

  def empty[@sp A: ClassTag]: AVector[A] = ofSize[A](defaultSize)

  def apply[@sp A: ClassTag](elems: A*): AVector[A] = {
    val array = Array.ofDim[A](if (elems.length <= defaultSize) defaultSize else elems.length)
    elems.copyToArray(array, 0, elems.length)
    unsafe(array, 0, elems.length, true)
  }

  def ofSize[@sp A: ClassTag](n: Int): AVector[A] = {
    val arr = new Array[A](n)
    unsafe(arr, 0, 0, true)
  }

  @inline def tabulate[@sp A: ClassTag](n: Int)(f: Int => A): AVector[A] = {
    assume(n >= 0)

    val arr = new Array[A](n)
    cfor(0)(_ < n, _ + 1) { i => arr(i) = f(i) }
    unsafe(arr)
  }

  @inline def tabulateE[A: ClassTag, E](
      n: Int
  )(f: Int => Either[E, A]): Either[E, AVector[A]] = {
    val arr = new Array[A](n)
    cfor(0)(_ < n, _ + 1) { i =>
      f(i) match {
        case Right(a) => arr(i) = a
        case Left(e)  => return Left(e)
      }
    }
    Right(unsafe(arr))
  }

  def tabulate[@sp A: ClassTag](n1: Int, n2: Int)(f: (Int, Int) => A): AVector[AVector[A]] = {
    assume(n1 >= 0 && n2 >= 0)
    tabulate(n1)(i1 => tabulate(n2)(f(i1, _)))
  }

  @inline def fill[@sp A: ClassTag](n: Int)(elem: => A): AVector[A] = {
    tabulate(n)(_ => elem)
  }

  @inline def fill[@sp A: ClassTag](n1: Int, n2: Int)(elem: => A): AVector[AVector[A]] = {
    tabulate(n1, n2)((_, _) => elem)
  }

  def from[@sp A: ClassTag](elems: IterableOnce[A]): AVector[A] = {
    unsafe(elems.iterator.toArray)
  }

  @inline def unsafe[@sp A: ClassTag](elems: Array[A], start: Int): AVector[A] = {
    val appendable = true
    unsafe(elems, start, elems.length, appendable)
  }

  @inline def unsafe[@sp A: ClassTag](elems: Array[A], start: Int, end: Int): AVector[A] = {
    assume(end <= elems.length)
    val appendable = true
    unsafe(elems, start, end, appendable)
  }

  private def unsafe[@sp A: ClassTag](
      _elems: Array[A],
      _start: Int,
      _end: Int,
      _appendable: Boolean
  ): AVector[A] =
    new AVector[A] {
      override var elems: Array[A]     = _elems
      override def start: Int          = _start
      override def end: Int            = _end
      override var appendable: Boolean = _appendable
    }

  @inline def unsafe[@sp A: ClassTag](elems: Array[A]): AVector[A] = unsafe(elems, 0)

  private[util] def nextPowerOfTwo(n: Int): Int = {
    val x = java.lang.Integer.highestOneBit(n)
    if (x == n) n else x * 2
  }
}
