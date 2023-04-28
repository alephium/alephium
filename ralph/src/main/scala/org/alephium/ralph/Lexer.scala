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

package org.alephium.ralph

import java.math.{BigDecimal, BigInteger}

import scala.util.control.NonFatal

import fastparse._
import fastparse.NoWhitespace._

import org.alephium.protocol.ALPH
import org.alephium.protocol.model.Address
import org.alephium.protocol.vm.{LockupScript, StatelessContext, Val}
import org.alephium.protocol.vm.Val.ByteVec
import org.alephium.ralph.ArithOperator._
import org.alephium.ralph.LogicalOperator._
import org.alephium.ralph.TestOperator._
import org.alephium.ralph.error.CompilerError
import org.alephium.util._

// scalastyle:off number.of.methods
object Lexer {
  def lowercase[Unknown: P]: P[Unit] = P(CharIn("a-z"))
  def uppercase[Unknown: P]: P[Unit] = P(CharIn("A-Z"))
  def digit[Unknown: P]: P[Unit]     = P(CharIn("0-9"))
  def hex[Unknown: P]: P[Unit]       = P(CharsWhileIn("0-9a-fA-F"))
  def letter[Unknown: P]: P[Unit]    = P(lowercase | uppercase)
  def newline[Unknown: P]: P[Unit]   = P(NoTrace(StringIn("\r\n", "\n")))

  private def id[Unknown: P, T](prefix: => P[Unit], func: String => T): P[T] =
    P(prefix ~ (letter | digit | "_").rep).!.filter(!Keyword.Used.exists(_)).map(func)
  def ident[Unknown: P]: P[Ast.Ident] = id(lowercase, Ast.Ident)
  def constantIdent[Unknown: P]: P[Ast.Ident] =
    id(uppercase.opaque("constant variables must start with an uppercase letter"), Ast.Ident)
  def typeId[Unknown: P]: P[Ast.TypeId] = id(uppercase, Ast.TypeId)
  def funcId[Unknown: P]: P[Ast.FuncId] =
    P(ident ~ "!".?.!).map { case (id, postfix) =>
      Ast.FuncId(id.name, postfix.nonEmpty)
    }

  private[ralph] def getSimpleName(obj: Object): String = {
    obj.getClass.getSimpleName.dropRight(1)
  }

  def token[Unknown: P](keyword: Keyword): P[Unit] = {
    keyword.name ~ !(letter | digit | "_")
  }

  def unused[Unknown: P]: P[Boolean] = token(Keyword.`@unused`).?.!.map(_.nonEmpty)
  def mut[Unknown: P]: P[Boolean]    = P(token(Keyword.mut).?.!).map(_.nonEmpty)

  /** @return
    *   - Failure if `allowMutable` is `false` and a `mut` declaration was found.
    *   - Else a boolean value: `true` if mut` declaration found, else `false`.
    */
  def mutMaybe[Unknown: P](allowMutable: Boolean): P[Boolean] =
    P(Index ~ mut) map { case (index, mutable) =>
      if (!allowMutable && mutable) {
        throw CompilerError.`Expected an immutable variable`(index)
      } else {
        mutable
      }
    }

  def lineComment[Unknown: P]: P[Unit] = P("//" ~ CharsWhile(_ != '\n', 0))
  def emptyChars[Unknown: P]: P[Unit]  = P((CharsWhileIn(" \t\r\n") | lineComment).rep)

  def hexNum[Unknown: P]: P[BigInteger] = P("0x") ~ hex.!.map(new BigInteger(_, 16))
  def integer[Unknown: P]: P[BigInteger] = P(
    Index ~ (CharsWhileIn("0-9_") ~ ("." ~ CharsWhileIn("0-9_")).? ~
      ("e" ~ "-".? ~ CharsWhileIn("0-9")).?).! ~
      CharsWhileIn(" ", 0) ~ token(Keyword.alph).?.!
  ).map { case (index, input, unit) =>
    try {
      var num = new BigDecimal(input.replaceAll("_", ""))
      if (unit == "alph") num = num.multiply(new BigDecimal(ALPH.oneAlph.toBigInt))
      num.toBigIntegerExact()
    } catch {
      case NonFatal(_) => throw CompilerError.`Invalid number`(input, index)
    }
  }
  def num[Unknown: P]: P[BigInteger] = negatable(P(hexNum | integer))
  def negatable[Unknown: P](p: => P[BigInteger]): P[BigInteger] =
    ("-".?.! ~ p).map {
      case ("-", i) => i.negate()
      case (_, i)   => i
    }
  def typedNum[Unknown: P]: P[Val] =
    P(Index ~ num ~ ("i" | "u").?.!)(
      sourcecode.Name(CompilerError.`an I256 or U256 value`.message),
      implicitly[P[_]]
    )
      .map {
        case (index, n, postfix) if Number.isNegative(n) || postfix == "i" =>
          I256.from(n) match {
            case Some(value) => Val.I256(value)
            case None        => throw CompilerError.`Expected an I256 value`(index, n)
          }

        case (index, n, _) =>
          U256.from(n) match {
            case Some(value) => Val.U256(value)
            case None        => throw CompilerError.`Expected an U256 value`(index, n)
          }
      }

  def bytesInternal[Unknown: P]: P[Val.ByteVec] =
    P(Index ~ CharsWhileIn("0-9a-zA-Z", 0).!).map { case (index, string) =>
      Hex.from(string) match {
        case Some(bytes) => ByteVec(bytes)
        case None =>
          Address.extractLockupScript(string) match {
            case Some(LockupScript.P2C(contractId)) => ByteVec(contractId.bytes)
            case _ => throw CompilerError.`Invalid byteVec`(string, index)
          }
      }
    }
  def bytes[Unknown: P]: P[Val.ByteVec] = P("#" ~ bytesInternal)
  def contractAddress[Unknown: P]: P[Val.ByteVec] =
    addressInternal.map {
      case (Val.Address(LockupScript.P2C(contractId)), _) => Val.ByteVec(contractId.bytes)
      case (addr, index) =>
        throw CompilerError.`Invalid contract address`(s"#@${addr.toBase58}", index)
    }

  def addressInternal[Unknown: P]: P[(Val.Address, Int)] =
    P(Index ~ CharsWhileIn("0-9a-zA-Z").!).map { case (index, input) =>
      val lockupScriptOpt = Address.extractLockupScript(input)
      lockupScriptOpt match {
        case Some(lockupScript) => (Val.Address(lockupScript), index)
        case None               => throw CompilerError.`Invalid address`(input, index)
      }
    }
  def address[Unknown: P]: P[Val.Address] = P("@" ~ addressInternal.map(_._1))

  def bool[Unknown: P]: P[Val.Bool] =
    P(token(Keyword.`true`) | token(Keyword.`false`)).!.map {
      case "true" => Val.Bool(true)
      case _      => Val.Bool(false)
    }

  def stringNoChar[Unknown: P]: P[String] =
    Pass("")
  def stringEscaping[Unknown: P]: P[String] =
    P("$$" | "$`").!.map(_.tail)
  def stringChars[Unknown: P]: P[String] =
    P(CharsWhile(c => c != '$' && c != '`').!)
  @SuppressWarnings(Array("org.wartremover.warts.IterableOps"))
  def stringPart[Unknown: P]: P[String] =
    P((stringChars | stringEscaping).rep(1).map(_.reduce(_ ++ _)) | stringNoChar)
  def stringChained[Ctx <: StatelessContext, T, Unknown: P](
      stringInterpolator: () => P[T]
  ): P[(AVector[String], Seq[T])] =
    P(stringPart ~ (stringInterpolator() ~ stringPart).rep).map { case (firstStringPart, pairs) =>
      val stringParts        = AVector(firstStringPart) ++ AVector.from(pairs.view.map(_._2))
      val interpolationParts = pairs.map(_._1)
      AVector.from(stringParts) -> interpolationParts
    }
  def string[T, Unknown: P](
      stringInterpolator: () => P[T]
  ): P[(AVector[String], Seq[T])] =
    P("`" ~ stringChained(stringInterpolator) ~ "`")

  def `abstract`[Unknown: P]: P[Boolean] = P(token(Keyword.Abstract).?.!).map(_.nonEmpty)

  def opByteVecAdd[Unknown: P]: P[Operator] = P("++").map(_ => Concat)
  def opAdd[Unknown: P]: P[Operator]        = P("+").map(_ => Add)
  def opSub[Unknown: P]: P[Operator]        = P("-").map(_ => Sub)
  def opMul[Unknown: P]: P[Operator]        = P("*").map(_ => Mul)
  def opExp[Unknown: P]: P[Operator]        = P("**").map(_ => Exp)
  def opModExp[Unknown: P]: P[Operator]     = P("|**|").map(_ => ModExp)
  def opDiv[Unknown: P]: P[Operator]        = P("/").map(_ => Div)
  def opMod[Unknown: P]: P[Operator]        = P("%").map(_ => Mod)
  def opModAdd[Unknown: P]: P[Operator]     = P("⊕" | "|+|").map(_ => ModAdd)
  def opModSub[Unknown: P]: P[Operator]     = P("⊖" | "|-|").map(_ => ModSub)
  def opModMul[Unknown: P]: P[Operator]     = P("⊗" | "|*|").map(_ => ModMul)
  def opSHL[Unknown: P]: P[Operator]        = P("<<").map(_ => SHL)
  def opSHR[Unknown: P]: P[Operator]        = P(">>").map(_ => SHR)
  def opBitAnd[Unknown: P]: P[Operator]     = P("&").map(_ => BitAnd)
  def opXor[Unknown: P]: P[Operator]        = P("^").map(_ => Xor)
  def opBitOr[Unknown: P]: P[Operator]      = P("|").map(_ => BitOr)
  def opEq[Unknown: P]: P[TestOperator]     = P("==").map(_ => Eq)
  def opNe[Unknown: P]: P[TestOperator]     = P("!=").map(_ => Ne)
  def opLt[Unknown: P]: P[TestOperator]     = P("<").map(_ => Lt)
  def opLe[Unknown: P]: P[TestOperator]     = P("<=").map(_ => Le)
  def opGt[Unknown: P]: P[TestOperator]     = P(">").map(_ => Gt)
  def opGe[Unknown: P]: P[TestOperator]     = P(">=").map(_ => Ge)
  def opAnd[Unknown: P]: P[LogicalOperator] = P("&&").map(_ => And)
  def opOr[Unknown: P]: P[LogicalOperator]  = P("||").map(_ => Or)
  def opNot[Unknown: P]: P[LogicalOperator] = P("!").map(_ => Not)

  sealed trait FuncModifier

  object FuncModifier {
    case object Pub     extends FuncModifier
    case object Payable extends FuncModifier

    def pub[Unknown: P]: P[FuncModifier]       = token(Keyword.pub).map(_ => Pub)
    def modifiers[Unknown: P]: P[FuncModifier] = P(pub)
  }

  val primTpes: Map[String, Type] =
    Type.primitives.map(tpe => (getSimpleName(tpe), tpe)).toArray.toMap
}
