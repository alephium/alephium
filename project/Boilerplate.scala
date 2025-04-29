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

import sbt._

/*
 * Extracted, with some modifications, from circe
 * https://github.com/circe/circe.git
 */
object Boilerplate {
  import scala.StringContext._

  private def _gen(templates: Seq[Template], dir: File) =
    templates.map { template =>
      val tgtFile = template.filename(dir)
      IO.write(tgtFile, template.body)
      tgtFile
    }

  def genSerde(): Unit = {
    genSrc(new File("./serde/src/main/scala/"))
    genTest(new File("./serde/src/test/scala/"))
  }

  def genSrc(dir: File): Seq[File]  = _gen(templatesSrc, dir)
  def genTest(dir: File): Seq[File] = _gen(templatesTest, dir)

  implicit class BlockHelper(val sc: StringContext) extends AnyVal {
    def block(args: Any*): String = {
      val interpolated = sc.standardInterpolator(treatEscapes, args)
      val rawLines     = interpolated.split('\n')
      val trimmedLines = rawLines.map(_.dropWhile(_.isWhitespace))
      trimmedLines.mkString("\n")
    }
  }

  private val header       = "// scalastyle:off\n// auto-generated boilerplate\n// format: off\n"
  private val maxAritySrc  = 10
  private val maxArityTest = 6

  class TemplateVals(val arity: Int) {
    val arities  = 0 until arity
    val synTypes = arities.map(n => s"A$n")
    val synVals  = arities.map(n => s"a$n")
    val `A..N`   = synTypes.mkString(", ")
    val `a..n`   = synVals.mkString(", ")
    val `(A..N)` = if (arity == 1) "Tuple1[A0]" else synTypes.mkString("(", ", ", ")")
    val `(a..n)` = if (arity == 1) "Tuple1(a)" else synVals.mkString("(", ", ", ")")
  }

  trait Template {
    def maxArity: Int = maxAritySrc
    def filename(root: File): File
    def content(tv: TemplateVals): String
    def range: IndexedSeq[Int] = 1 to maxArity
    def body: String = {
      val headerLines = header.split('\n')
      val raw =
        range.map(n => content(new TemplateVals(n)).split('\n').filterNot(_.isEmpty))
      val preBody   = raw.head.takeWhile(_.startsWith("|")).map(_.tail)
      val instances = raw.flatMap(_.filter(_.startsWith("+")).map(_.tail))
      val postBody  = raw.head.dropWhile(_.startsWith("|")).dropWhile(_.startsWith("+")).map(_.tail)
      (headerLines ++ preBody ++ instances ++ postBody).mkString("\n")
    }
  }

  trait TemplateTest extends Template {
    override def maxArity: Int = maxArityTest
  }

  val templatesSrc: Seq[Template]  = Seq(GenProductSerde, GenProductSerializer)
  val templatesTest: Seq[Template] = Seq(GenProductSerdeTest)

  object GenProductSerde extends Template {
    override def filename(root: File): File =
      root / "org" / "alephium" / "serde" / "ProductSerde.scala"

    // scalastyle:off method.length
    override def content(tv: TemplateVals): String = {
      import tv._

      val serdeInstances = synTypes.map(t => s"serde$t: Serde[$t]").mkString(", ")
      val serializes = synVals
        .zip(synTypes)
        .map { case (v, t) => s"serde$t.serialize($v)" }
        .mkString(" ++ ")

      def rest(n: Int): String = if (n == 0) "rest" else s"pair${n - 1}.rest"
      val deserializes = arities
        .zip(synTypes)
        .map { case (n, t) => s"pair$n <- serde$t._deserialize(${rest(n)})" }
        .mkString("; ")

      val deVals = arities.map(n => s"pair$n.value").mkString(", ")

      block"""
        |package org.alephium.serde
        |
        |import akka.util.ByteString
        |
        |private[serde] trait ProductSerde {
        +
        +  final def forProduct$arity[${`A..N`}, T](pack: (${`A..N`}) => T, unpack: T => (${`A..N`}))(implicit
        +    $serdeInstances
        +  ): Serde[T] = new Serde[T] {
        +    override def serialize(input: T): ByteString = {
        +      val (${`a..n`}) = unpack(input)
        +      $serializes
        +    }
        +
        +    override def _deserialize(rest: ByteString): SerdeResult[Staging[T]] = {
        +      for {
        +        $deserializes
        +      } yield Staging(pack($deVals), pair${arity - 1}.rest)
        +    }
        +  }
        +
        +  final def tuple$arity[${`A..N`}](implicit $serdeInstances): Serde[(${`A..N`})] = new Serde[(${`A..N`})] {
        +    override def serialize(input: (${`A..N`})): ByteString = {
        +      val (${`a..n`}) = input
        +      $serializes
        +    }
        +
        +    override def _deserialize(rest: ByteString): SerdeResult[Staging[(${`A..N`})]] = {
        +      for {
        +        $deserializes
        +      } yield Staging(($deVals), pair${arity - 1}.rest)
        +    }
        +  }
        |}
      """
    }
    // scalastyle:on
  }

  object GenProductSerializer extends Template {
    override def filename(root: File): File =
      root / "org" / "alephium" / "serde" / "ProductSerializer.scala"

    // scalastyle:off method.length
    override def content(tv: TemplateVals): String = {
      import tv._

      val serInstances   = synTypes.map(t => s"serde$t: Serializer[$t]").mkString(", ")
      val serdeInstances = synTypes.map(t => s"serde$t: Serde[$t]").mkString(", ")
      val serializes = synVals
        .zip(synTypes)
        .map { case (v, t) => s"serde$t.serialize($v)" }
        .mkString(" ++ ")

      def rest(n: Int): String = if (n == 0) "rest" else s"pair${n - 1}.rest"
      val deserializes = arities
        .zip(synTypes)
        .map { case (n, t) => s"pair$n <- serde$t._deserialize(${rest(n)})" }
        .mkString("; ")

      val deVals = arities.map(n => s"pair$n.value").mkString(", ")

      block"""
        |package org.alephium.serde
        |
        |import akka.util.ByteString
        |
        |private[serde] trait ProductSerializer {
        +
        +  final def forProduct$arity[${`A..N`}, T](unpack: T => (${`A..N`}))(implicit
        +    $serInstances
        +  ): Serializer[T] = new Serializer[T] {
        +    override def serialize(input: T): ByteString = {
        +      val (${`a..n`}) = unpack(input)
        +      $serializes
        +    }
        +  }
        +
        +  final def tuple$arity[${`A..N`}](implicit $serInstances): Serializer[(${`A..N`})] = new Serializer[(${`A..N`})] {
        +    override def serialize(input: (${`A..N`})): ByteString = {
        +      val (${`a..n`}) = input
        +      $serializes
        +    }
        +  }
        |}
      """
    }
    // scalastyle:on
  }

  object GenProductSerdeTest extends TemplateTest {
    override def filename(root: File): File =
      root / "org" / "alephium" / "serde" / "ProductSerdeSpec.scala"

    override def content(tv: TemplateVals): String = {
      import tv._

      val fields   = synVals.map(v => s"$v: Int").mkString(", ")
      val types    = arities.map(_ => "Int").mkString(", ")
      val accesses = synVals.map(v => s"t.$v").mkString(", ")

      block"""
        |package org.alephium.serde
        |
        |import org.alephium.util.AlephiumSpec
        |
        |class ProductSerdeSpec extends AlephiumSpec {
        |
        |  behavior of "Serde for case class"
        +
        +  case class Test$arity($fields)
        +  object Test$arity {
        +    implicit val serde: Serde[Test$arity] = Serde.forProduct$arity(apply, t => ($accesses))
        +  }
        +
        +  it should "serde $arity fields" in {
        +    forAll { ($fields) =>
        +      val input  = Test$arity(${`a..n`})
        +      val output = deserialize[Test$arity](serialize(input)).rightValue
        +      output is input
        +    }
        +  }
        +
        +  it should "serde $arity tuple" in {
        +    forAll { ($fields) =>
        +      val input  = (${`a..n`})
        +      val serde = Serde.tuple$arity[$types]
        +      val output = serde.deserialize(serde.serialize(input)).rightValue
        +      output is input
        +    }
        +  }
        |}
      """
    }
  }
}
