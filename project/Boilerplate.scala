import sbt._

/*
 * Copied, with some modifications, from circe
 */
object Boilerplate {
  import scala.StringContext._

  private def _gen(templates: Seq[Template], dir: File) = templates.map { template =>
    val tgtFile = template.filename(dir)
    IO.write(tgtFile, template.body)
    tgtFile
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

  private val header       = "// auto-generated boilerplate"
  private val maxAritySrc  = 22
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

  val templatesSrc: Seq[Template]  = Seq(GenProductSerde)
  val templatesTest: Seq[Template] = Seq(GenProductSerdeTest)

  object GenProductSerde extends Template {
    override def filename(root: File): File = root / "org" / "alephium" / "ProductSerde.scala"

    override def content(tv: TemplateVals): String = {
      import tv._

      val instances = synTypes.map(t => s"serde$t: Serde[$t]").mkString(", ")
      val serializes = synVals
        .zip(synTypes)
        .map { case (v, t) => s"serde$t.serialize($v)" }
        .mkString(" ++ ")

      val deserializes = arities
        .zip(synVals)
        .zip(synTypes)
        .map { case ((n, v), t) => s"($v, rest${n + 1}) <- serde$t._deserialize(rest$n)" }
        .mkString("; ")

      block"""
        |package org.alephium.serde
        |
        |import akka.util.ByteString
        |
        |import scala.util.Try
        |
        |private[serde] trait ProductSerde {
        +
        +  final def forProduct$arity[${`A..N`}, T](pack: (${`A..N`}) => T, unpack: T => (${`A..N`}))(implicit
        +    $instances
        +  ): Serde[T] = new Serde[T] {
        +    override def serialize(input: T): ByteString = {
        +      val (${`a..n`}) = unpack(input)
        +      $serializes
        +    }
        +
        +    override def _deserialize(rest0: ByteString): Try[(T, ByteString)] = {
        +      for {
        +        $deserializes
        +      } yield (pack(${`a..n`}), rest$arity)
        +    }
        +  }
        |}
      """
    }
  }

  object GenProductSerdeTest extends TemplateTest {
    override def filename(root: File): File = root / "org" / "alephium" / "ProductSerdeSpec.scala"

    override def content(tv: TemplateVals): String = {
      import tv._

      val fields   = synVals.map(v => s"$v: Int").mkString(", ")
      val accesses = synVals.map(v => s"t.$v").mkString(", ")

      block"""
        |package org.alephium.serde
        |
        |import org.scalatest.TryValues._
        |import org.scalatest.{FlatSpecLike, Matchers}
        |import org.scalatest.prop.GeneratorDrivenPropertyChecks
        |
        |class ProductSerdeSpec extends FlatSpecLike with GeneratorDrivenPropertyChecks with Matchers {
        |
        |  behavior of "Serde for case class"
        +
        +  case class Test$arity($fields)
        +  object Test$arity {
        +    implicit val serde: Serde[Test$arity] = Serde.forProduct$arity(apply, t => ($accesses))
        +  }
        +
        +  it should "serde $arity fields correctly" in {
        +    forAll { ($fields) =>
        +      val input  = Test$arity(${`a..n`})
        +      val output = deserialize[Test$arity](serialize(input)).success.value
        +      output shouldBe input
        +    }
        +  }
        |}
      """
    }
  }
}
