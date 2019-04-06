package org.alephium.util

import org.alephium.macros.HPC

import scala.reflect.ClassTag
import scala.{inline, specialized => sp}

// scalastyle:off number.of.methods return
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
    assert(nonEmpty)
    elems(start)
  }

  def last: A = {
    assert(nonEmpty)
    elems(end - 1)
  }

  def init: AVector[A] = {
    assert(nonEmpty)
    AVector.unsafe(elems, start, end - 1, false)
  }

  def tail: AVector[A] = {
    assert(nonEmpty)
    AVector.unsafe(elems, start + 1, end, appendable)
  }

  def apply(i: Int): A = {
    assert(i >= 0 && i < length)

    elems(start + i)
  }

  private[util] def ensureSize(n: Int): Unit = {
    assert(n >= 0)

    val goal = start + n
    if (goal > capacity) {
      val size =
        if (goal <= AVector.defaultSize) AVector.defaultSize
        else AVector.nextPowerOfTwo(goal)
      growTo(size)
    }
  }

  private[util] def growTo(size: Int): Unit = {
    val arr = new Array[A](size)
    System.arraycopy(elems, 0, arr, 0, capacity)
    elems = arr
  }

  def :+(elem: A): AVector[A] = {
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

  def ++(that: AVector[A]): AVector[A] = {
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

  def contains(elem: A): Boolean = {
    cfor(start)(_ < end, _ + 1) { i =>
      if (elems(i) == elem) return true
    }
    false
  }

  def exists(f: A => Boolean): Boolean = {
    foreach { a =>
      if (f(a)) { return true }
    }
    false
  }

  def existsWithIndex(f: (A, Int) => Boolean): Boolean = {
    foreachWithIndex { (a, i) =>
      if (f(a, i)) { return true }
    }
    false
  }

  def forall(f: A => Boolean): Boolean = {
    foreach { a =>
      if (!f(a)) { return false }
    }
    true
  }

  def forallWithIndex(f: (A, Int) => Boolean): Boolean = {
    foreachWithIndex { (a, i) =>
      if (!f(a, i)) { return false }
    }
    true
  }

  def slice(from: Int, until: Int): AVector[A] = {
    assert(from >= 0 && from <= until && until <= length)

    val newAppendable = if (until == length) appendable else false
    AVector.unsafe(elems, start + from, start + until, newAppendable)
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
    val arr       = new Array[A](length)
    val rightmost = end - 1
    cfor(0)(_ < length, _ + 1) { i =>
      arr(i) = elems(rightmost - i)
    }
    AVector.unsafe(arr)
  }

  def foreach[U](f: A => U): Unit = {
    cfor(start)(_ < end, _ + 1) { i =>
      f(elems(i))
      ()
    }
  }

  def foreachWithIndex[U](f: (A, Int) => U): Unit = {
    cfor(start)(_ < end, _ + 1) { i =>
      f(elems(i), i - start)
      ()
    }
  }

  def map[@sp B: ClassTag](f: A => B): AVector[B] = {
    AVector.tabulate(length) { i =>
      f(apply(i))
    }
  }

  def mapWithIndex[@sp B: ClassTag](f: (A, Int) => B): AVector[B] = {
    AVector.tabulate(length) { i =>
      f(apply(i), i)
    }
  }

  def filter(p: A => Boolean): AVector[A] = {
    filterImpl(p, true)
  }

  def filterNot(p: A => Boolean): AVector[A] = {
    filterImpl(p, false)
  }

  def traverse[L, R: ClassTag](f: A => Either[L, R]): Either[L, AVector[R]] = {
    var result = AVector.empty[R]
    foreach { elem =>
      f(elem) match {
        case Left(l)  => return Left(l)
        case Right(r) => result = result :+ r
      }
    }
    Right(result)
  }

  def foreachF[L](f: A => Either[L, Unit]): Either[L, Unit] = {
    foreach { elem =>
      f(elem) match {
        case Left(l)  => return Left(l)
        case Right(_) => ()
      }
    }
    Right(())
  }

  def foreachWithIndexF[L](f: (A, Int) => Either[L, Unit]): Either[L, Unit] = {
    foreachWithIndex { (elem, i) =>
      f(elem, i) match {
        case Left(l)  => return Left(l)
        case Right(_) => ()
      }
    }
    Right(())
  }

  @inline private def filterImpl(p: A => Boolean, target: Boolean): AVector[A] = {
    fold(AVector.empty[A]) { (acc, elem) =>
      if (p(elem) == target) acc :+ elem else acc
    }
  }

  def withFilter(p: A => Boolean): WithFilter = new WithFilter(p)

  class WithFilter(p: A => Boolean) {
    def map[@sp B: ClassTag](f: A => B): AVector[B] = {
      fold(AVector.empty[B]) { (acc, elem) =>
        if (p(elem)) acc :+ f(elem) else acc
      }
    }

    def flatMap[@sp B: ClassTag](f: A => AVector[B]): AVector[B] = {
      fold(AVector.empty[B]) { (acc, elem) =>
        if (p(elem)) acc ++ f(elem) else acc
      }
    }

    def foreach[U](f: A => U): Unit = {
      foreach { elem =>
        if (p(elem)) f(elem)
      }
    }

    def withFilter(q: A => Boolean): WithFilter = new WithFilter(elem => p(elem) && q(elem))
  }

  def fold[B](zero: B)(f: (B, A) => B): B = {
    var res = zero
    cfor(start)(_ < end, _ + 1) { i =>
      res = f(res, elems(i))
    }
    res
  }

  def reduce(op: (A, A) => A): A = {
    reduceBy(identity)(op)
  }

  def reduceBy[B: ClassTag](f: A => B)(op: (B, B) => B): B = {
    assert(nonEmpty)

    var acc = f(elems(start))
    cfor(start + 1)(_ < end, _ + 1) { i =>
      acc = op(acc, f(elems(i)))
    }
    acc
  }

  def flatMap[B: ClassTag](f: A => AVector[B]): AVector[B] = {
    fold(AVector.empty[B]) { (acc, elem) =>
      acc ++ f(elem)
    }
  }

  def flatMapWithIndex[B: ClassTag](f: (A, Int) => AVector[B]): AVector[B] = {
    val (_, xs) = fold((0, AVector.empty[B])) {
      case ((i, acc), elem) =>
        (i + 1, acc ++ f(elem, i))
    }
    xs
  }

  def scanLeft[B: ClassTag](zero: B)(op: (B, A) => B): AVector[B] = {
    val arr = new Array[B](length + 1)
    var acc = zero
    arr(0) = acc
    cfor(0)(_ < length, _ + 1) { i =>
      acc = op(acc, apply(i))
      arr(i + 1) = acc
    }
    AVector.unsafe(arr)
  }

  def indexWhere(f: A => Boolean): Int = {
    cfor(start)(_ < end, _ + 1) { i =>
      if (f(elems(i))) return i - start
    }
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
    fold(num.zero) { (sum, elem) =>
      num.plus(sum, f(elem))
    }
  }

  def max(implicit cmp: Ordering[A]): A = {
    assert(nonEmpty)

    reduce((x, y) => if (cmp.gteq(x, y)) x else y)
  }

  def min(implicit cmp: Ordering[A]): A = {
    assert(nonEmpty)

    reduce((x, y) => if (cmp.lteq(x, y)) x else y)
  }

  def maxBy[B](f: A => B)(implicit cmp: Ordering[B]): A = {
    assert(nonEmpty)

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
    assert(nonEmpty)

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

  def toArray: Array[A] = {
    val arr = new Array[A](length)
    System.arraycopy(elems, start, arr, 0, length)
    arr
  }

  def toIterable: Iterable[A] = {
    new Iterable[A] {
      override def size: Int    = length
      def iterator: Iterator[A] = elems.iterator.slice(start, end)
      override def foreach[U](f: A => U): Unit = self.foreach(f)
    }
  }

  def indices: Range = 0 until length

  def mkString(start: String, sep: String, end: String): String = {
    toIterable.mkString(start, sep, end)
  }

  def mkString(sep: String): String = mkString("", sep, "")

  override def equals(obj: Any): Boolean = obj match {
    case that: AVector[A] =>
      if (length == that.length && ct == that.ct) {
        cfor(0)(_ < length, _ + 1) { i =>
          if (apply(i) != that(i)) return false
        }
        true
      } else false
    case _ => false
  }

  // scalastyle:off magic.number
  override def hashCode(): Int = {
    var code: Int = 0xd55d283e
    cfor(start)(_ < end, _ + 1) { i =>
      code = (code * 19) + elems(i).##
    }
    code
  }
}
// scalastyle:on

object AVector {
  import HPC.cfor

  private[util] val defaultSize = 8

  def empty[@sp A: ClassTag]: AVector[A] = ofSize[A](defaultSize)

  def apply[@sp A: ClassTag](elems: A*): AVector[A] = {
    unsafe(elems.toArray)
  }

  def ofSize[@sp A: ClassTag](n: Int): AVector[A] = {
    val arr = new Array[A](n)
    unsafe(arr, 0, 0, true)
  }

  @inline def tabulate[@sp A: ClassTag](n: Int)(f: Int => A): AVector[A] = {
    assert(n >= 0)

    val arr = new Array[A](n)
    cfor(0)(_ < n, _ + 1) { i =>
      arr(i) = f(i)
    }
    unsafe(arr)
  }

  def tabulate[@sp A: ClassTag](n1: Int, n2: Int)(f: (Int, Int) => A): AVector[AVector[A]] = {
    assert(n1 >= 0 && n2 >= 0)
    tabulate(n1)(i1 => tabulate(n2)(f(i1, _)))
  }

  @inline def fill[@sp A: ClassTag](n: Int)(elem: => A): AVector[A] = {
    assert(n >= 0)
    tabulate(n)(_ => elem)
  }

  def from[@sp A: ClassTag](elems: Iterable[A]): AVector[A] = {
    unsafe(elems.toArray)
  }

  def fromIterator[@sp A: ClassTag](it: Iterator[A]): AVector[A] = {
    unsafe(it.toArray)
  }

  @inline private def unsafe[@sp A: ClassTag](elems: Array[A], start: Int): AVector[A] = {
    val appendable = true
    unsafe(elems, start, elems.length, appendable)
  }

  private def unsafe[@sp A: ClassTag](_elems: Array[A],
                                      _start: Int,
                                      _end: Int,
                                      _appendable: Boolean): AVector[A] =
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
