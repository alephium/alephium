package org.alephium.util

import akka.util.ByteString
import org.bouncycastle.util.encoders.{Hex => BHex}

import scala.language.experimental.macros
import scala.reflect.macros.blackbox

object Hex {
  def unsafeFrom(s: String): ByteString = {
    ByteString.fromArrayUnsafe(BHex.decode(s))
  }

  def toHexString(input: IndexedSeq[Byte]): String = {
    BHex.toHexString(input.toArray)
  }

  implicit class HexStringSyntax(val sc: StringContext) extends AnyVal {
    def hex(): ByteString = macro hexImpl
  }

  def hexImpl(c: blackbox.Context)(): c.Expr[ByteString] = {
    import c.universe._
    c.prefix.tree match {
      case Apply(_, List(Apply(_, List(Literal(Constant(s: String)))))) =>
        val bs = BHex.decode(s)
        c.Expr(q"akka.util.ByteString($bs)")
    }
  }
}
