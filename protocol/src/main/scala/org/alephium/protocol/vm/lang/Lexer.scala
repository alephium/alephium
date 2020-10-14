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

package org.alephium.protocol.vm.lang

import java.math.BigInteger

import fastparse._
import fastparse.NoWhitespace._

import org.alephium.protocol.model.Address
import org.alephium.protocol.vm.Val
import org.alephium.protocol.vm.Val.ByteVec
import org.alephium.util._

// scalastyle:off number.of.methods
object Lexer {
  def lowercase[_: P]: P[Unit] = P(CharIn("a-z"))
  def uppercase[_: P]: P[Unit] = P(CharIn("A-Z"))
  def digit[_: P]: P[Unit]     = P(CharIn("0-9"))
  def hex[_: P]: P[Unit]       = P(CharsWhileIn("0-9a-fA-F"))
  def letter[_: P]: P[Unit]    = P(lowercase | uppercase)
  def newline[_: P]: P[Unit]   = P(NoTrace(StringIn("\r\n", "\n")))

  def ident[_: P]: P[Ast.Ident] =
    P(lowercase ~ (letter | digit | "_").rep).!.filter(!keywordSet.contains(_)).map(Ast.Ident)
  def typeId[_: P]: P[Ast.TypeId] =
    P(uppercase ~ (letter | digit | "_").rep).!.filter(!keywordSet.contains(_)).map(Ast.TypeId)
  def funcId[_: P]: P[Ast.FuncId] = P(ident ~ "!".?.!).map {
    case (id, postfix) => Ast.FuncId(id.name, postfix.nonEmpty)
  }

  private[lang] def getSimpleName(obj: Object): String = {
    obj.getClass.getSimpleName.dropRight(1)
  }

  def keyword[_: P](s: String): P[Unit] = s ~ !(letter | digit | "_")
  def mut[_: P]: P[Boolean]             = P(keyword("mut").?.!).map(_.nonEmpty)

  def lineComment[_: P]: P[Unit] = P("//" ~ CharsWhile(_ != '\n', 0))
  def emptyChars[_: P]: P[Unit]  = P((CharsWhileIn(" \t\r\n") | lineComment).rep)

  def hexNum[_: P]: P[BigInteger] = P("0x" ~ hex).!.map(new BigInteger(_, 16))
  def decNum[_: P]: P[BigInteger] = P(CharsWhileIn("0-9")).!.map(new BigInteger(_))
  def num[_: P]: P[BigInteger]    = negatable(P(hexNum | decNum))
  def negatable[_: P](p: => P[BigInteger]): P[BigInteger] = ("-".?.! ~ p).map {
    case ("-", i) => i.negate()
    case (_, i)   => i
  }
  def typedNum[_: P]: P[Val] =
    P(num ~ ("i" | "u" | "I" | "U" | "b").?.!)
      .map {
        case (n, "i") =>
          I64.from(n) match {
            case Some(value) => Val.I64(value)
            case None        => throw Compiler.Error(s"Invalid I64 value: $n")
          }
        case (n, "I") =>
          I256.from(n) match {
            case Some(value) => Val.I256(value)
            case None        => throw Compiler.Error(s"Invalid I256 value: $n")
          }
        case (n, "U") =>
          U256.from(n) match {
            case Some(value) => Val.U256(value)
            case None        => throw Compiler.Error(s"Invalid U256 value: $n")
          }
        case (n, "b") =>
          U64.from(n).filter(u => u.v < 0x100) match {
            case Some(value) => Val.Byte(value.v.toByte)
            case None        => throw Compiler.Error(s"Invalid Byte value: $n")
          }
        case (n, _) =>
          U64.from(n) match {
            case Some(value) => Val.U64(value)
            case None        => throw Compiler.Error(s"Invalid U64 value: $n")
          }
      }

  def bytesInternal[_: P]: P[Val.ByteVec] = P(hex).!.map { hexString =>
    val byteVecOpt = Hex.asArraySeq(hexString).map(ByteVec(_))
    byteVecOpt match {
      case Some(byteVec) => byteVec
      case None          => throw Compiler.Error(s"Invalid Byte32 value: $hexString")
    }
  }
  def bytes[_: P]: P[Val.ByteVec] = P("#" ~ bytesInternal)

  def addressInternal[_: P]: P[Val.Address] = P(CharsWhileIn("0-9a-zA-Z")).!.map { input =>
    val lockupScriptOpt = Address.extractLockupScript(input)
    lockupScriptOpt match {
      case Some(lockupScript) => Val.Address(lockupScript)
      case None               => throw Compiler.Error(s"Invalid address: $input")
    }
  }
  def address[_: P]: P[Val.Address] = P("@" ~ addressInternal)

  def bool[_: P]: P[Val.Bool] = P(keyword("true") | keyword("false")).!.map {
    case "true" => Val.Bool(true)
    case _      => Val.Bool(false)
  }

  def opAdd[_: P]: P[Operator]        = P("+").map(_  => Add)
  def opSub[_: P]: P[Operator]        = P("-").map(_  => Sub)
  def opMul[_: P]: P[Operator]        = P("*").map(_  => Mul)
  def opDiv[_: P]: P[Operator]        = P("/").map(_  => Div)
  def opMod[_: P]: P[Operator]        = P("%").map(_  => Mod)
  def opEq[_: P]: P[TestOperator]     = P("==").map(_ => Eq)
  def opNe[_: P]: P[TestOperator]     = P("!=").map(_ => Ne)
  def opLt[_: P]: P[TestOperator]     = P("<").map(_  => Lt)
  def opLe[_: P]: P[TestOperator]     = P("<=").map(_ => Le)
  def opGt[_: P]: P[TestOperator]     = P(">").map(_  => Gt)
  def opGe[_: P]: P[TestOperator]     = P(">=").map(_ => Ge)
  def opAnd[_: P]: P[LogicalOperator] = P("&&").map(_ => And)
  def opOr[_: P]: P[LogicalOperator]  = P("||").map(_ => Or)
  def opNot[_: P]: P[LogicalOperator] = P("!").map(_  => Not)

  sealed trait FuncModifier
  case object Pub     extends FuncModifier
  case object Payable extends FuncModifier

  def pub[_: P]: P[FuncModifier]          = keyword("pub").map(_ => Pub)
  def payable[_: P]: P[FuncModifier]      = keyword("payable").map(_ => Payable)
  def funcModifier[_: P]: P[FuncModifier] = P(pub | payable)

  // format: off
  def keywordSet: Set[String] =
    Set("TxContract", "AssetScript", "TxScript", "let", "mut", "fn", "return", "true", "false", "if", "else", "while")
  // format: on

  val primTpes: Map[String, Type] =
    Type.primitives.map(tpe => (getSimpleName(tpe), tpe)).toArray.toMap
}
