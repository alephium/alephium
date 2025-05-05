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

import java.io.IOException
import java.nio.file.{Files => JFiles, FileVisitResult, Path, SimpleFileVisitor}
import java.nio.file.attribute.BasicFileAttributes

import scala.annotation.nowarn
import scala.collection.mutable
import scala.language.implicitConversions

import akka.util.ByteString
import org.scalacheck.{Arbitrary, Gen, Shrink}
import org.scalacheck.Arbitrary._
import org.scalactic.Equality
import org.scalactic.source.Position
import org.scalatest.{Assertion, BeforeAndAfterAll, OptionValues}
import org.scalatest.concurrent.{Eventually, IntegrationPatience, ScalaFutures}
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.dsl.ResultOfATypeInvocation
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.scalacheck.ScalaCheckDrivenPropertyChecks

trait AlephiumSpec
    extends AnyFlatSpecLike
    with OptionValues
    with ScalaCheckDrivenPropertyChecks
    with AlephiumFixture
    with BeforeAndAfterAll {
  @nowarn implicit protected def noShrink[A]: Shrink[A] = Shrink(_ => Stream.empty)

  override def afterAll(): Unit = {
    super.afterAll()
    AlephiumSpec.clean()
  }
}

object AlephiumSpec {
  private val cleanTasks: mutable.ArrayBuffer[() => Unit] = mutable.ArrayBuffer.empty

  def addCleanTask(task: () => Unit): Unit = cleanTasks.addOne(task)

  def delete(path: Path): Unit = {
    if (JFiles.exists(path)) {
      JFiles.walkFileTree(
        path,
        new SimpleFileVisitor[Path] {
          override def visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult = {
            JFiles.delete(file)
            FileVisitResult.CONTINUE
          }
          override def postVisitDirectory(dir: Path, exc: IOException): FileVisitResult = {
            JFiles.delete(dir)
            FileVisitResult.CONTINUE
          }
        }
      )
      ()
    }
  }

  def clean(): Unit = {
    cleanTasks.foreach(task => task())
    cleanTasks.clear()

    delete(Files.testRootPath(Env.Test))
    delete(Files.testRootPath(Env.Integration))
  }
}

trait AlephiumFutureSpec
    extends AlephiumSpec
    with ScalaFutures
    with Eventually
    with IntegrationPatience

trait AlephiumFixture extends Matchers {

  lazy val posLongGen: Gen[Long] = Gen.chooseNum(0L, Long.MaxValue)
  lazy val negLongGen: Gen[Long] = Gen.chooseNum(Long.MinValue, -1L)

  implicit lazy val bytesArb: Arbitrary[AVector[Byte]] = Arbitrary(
    arbitrary[List[Byte]].map(AVector.from)
  )
  implicit lazy val i32Arb: Arbitrary[I32]   = Arbitrary(arbitrary[Int].map(I32.unsafe))
  implicit lazy val u32Arb: Arbitrary[U32]   = Arbitrary(arbitrary[Int].map(U32.unsafe))
  implicit lazy val i64Arb: Arbitrary[I64]   = Arbitrary(arbitrary[Long].map(I64.from))
  implicit lazy val u64Arb: Arbitrary[U64]   = Arbitrary(arbitrary[Long].map(U64.unsafe))
  implicit lazy val u256Arb: Arbitrary[U256] = Arbitrary(posLongGen.map(U256.unsafe))

  // scalastyle:off no.should
  implicit class IsOps[A: Equality](left: A)(implicit pos: Position) {
    def is(right: A): Assertion                             = left shouldEqual right
    def is(right: ResultOfATypeInvocation[_]): Assertion    = left shouldBe right
    def isnot(right: A): Assertion                          = left should not equal right
    def isnot(right: ResultOfATypeInvocation[_]): Assertion = left should not be right
  }

  implicit class IsEOps[A: Equality, L](left: Either[L, A])(implicit pos: Position) {
    def rightValue: A =
      left match {
        case Left(error) => throw new AssertionError(error)
        case Right(a)    => a
      }

    def leftValue: L =
      left match {
        case Left(error) => error
        case Right(a)    => throw new AssertionError(a)
      }

    def isE(right: A): Assertion                             = rightValue shouldEqual right
    def isE(right: ResultOfATypeInvocation[_]): Assertion    = rightValue shouldBe right
    def isnotE(right: A): Assertion                          = rightValue should not equal right
    def isnotE(right: ResultOfATypeInvocation[_]): Assertion = rightValue should not be right
  }
  // scalastyle:on

  import java.math.BigInteger
  implicit class BigIntegerWrapper(val n: BigInteger) extends Ordered[BigIntegerWrapper] {
    override def compare(that: BigIntegerWrapper): Int = this.n.compareTo(that.n)
  }

  implicit val bigIntegerNumeric: Numeric[BigInteger] = new Numeric[BigInteger] {
    def fromInt(x: Int): java.math.BigInteger = BigInt(x).bigInteger
    def minus(x: java.math.BigInteger, y: java.math.BigInteger): java.math.BigInteger =
      x.subtract(y)
    def negate(x: java.math.BigInteger): java.math.BigInteger = x.negate()
    def parseString(str: String): Option[java.math.BigInteger] =
      scala.util.Try(new BigInteger(str)).toOption
    def plus(x: java.math.BigInteger, y: java.math.BigInteger): java.math.BigInteger = x.add(y)
    def times(x: java.math.BigInteger, y: java.math.BigInteger): java.math.BigInteger =
      x.multiply(y)
    def toDouble(x: java.math.BigInteger): Double                      = x.doubleValue
    def toFloat(x: java.math.BigInteger): Float                        = x.floatValue
    def toInt(x: java.math.BigInteger): Int                            = x.intValue
    def toLong(x: java.math.BigInteger): Long                          = x.longValue
    def compare(x: java.math.BigInteger, y: java.math.BigInteger): Int = x.compareTo(y)
  }

  implicit def intToBigInteger(x: Int): BigInteger       = BigInt(x).bigInteger
  implicit def bigIntToBigInteger(x: BigInt): BigInteger = x.bigInteger

  def bytesGen(size: Int): Gen[ByteString] =
    Gen.listOfN(size, arbitrary[Byte]).map(ByteString.apply)
}
