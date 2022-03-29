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
import org.alephium.protocol.vm.{LockupScript, Val}
import org.alephium.protocol.vm.Val.ByteVec
import org.alephium.protocol.vm.lang.ArithOperator._
import org.alephium.protocol.vm.lang.LogicalOperator._
import org.alephium.protocol.vm.lang.TestOperator._
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
  def funcId[_: P]: P[Ast.FuncId] =
    P(ident ~ "!".?.!).map { case (id, postfix) =>
      Ast.FuncId(id.name, postfix.nonEmpty)
    }

  private[lang] def getSimpleName(obj: Object): String = {
    obj.getClass.getSimpleName.dropRight(1)
  }

  def keyword[_: P](s: String): P[Unit] = s ~ !(letter | digit | "_")
  def mut[_: P]: P[Boolean]             = P(keyword("mut").?.!).map(_.nonEmpty)

  def lineComment[_: P]: P[Unit] = P("//" ~ CharsWhile(_ != '\n', 0))
  def emptyChars[_: P]: P[Unit]  = P((CharsWhileIn(" \t\r\n") | lineComment).rep)

  def hexNum[_: P]: P[BigInteger] = P("0x") ~ hex.!.map(new BigInteger(_, 16))
  def decNum[_: P]: P[BigInteger] = P(CharsWhileIn("0-9")).!.map(new BigInteger(_))
  def num[_: P]: P[BigInteger]    = negatable(P(hexNum | decNum))
  def negatable[_: P](p: => P[BigInteger]): P[BigInteger] =
    ("-".?.! ~ p).map {
      case ("-", i) => i.negate()
      case (_, i)   => i
    }
  def typedNum[_: P]: P[Val] =
    P(num ~ ("i" | "u").?.!)
      .map {
        case (n, "i") =>
          I256.from(n) match {
            case Some(value) => Val.I256(value)
            case None        => throw Compiler.Error(s"Invalid I256 value: $n")
          }
        case (n, _) =>
          U256.from(n) match {
            case Some(value) => Val.U256(value)
            case None        => throw Compiler.Error(s"Invalid U256 value: $n")
          }
      }

  def bytesInternal[_: P]: P[Val.ByteVec] =
    P(CharsWhileIn("0-9a-zA-Z")).!.map { string =>
      Hex.from(string) match {
        case Some(bytes) => ByteVec(bytes)
        case None =>
          Address.extractLockupScript(string) match {
            case Some(LockupScript.P2C(contractId)) => ByteVec(contractId.bytes)
            case _                                  => throw Compiler.Error(s"Invalid byteVec: $string")
          }
      }
    }
  def bytes[_: P]: P[Val.ByteVec] = P("#" ~ bytesInternal)
  def contractAddress[_: P]: P[Val.ByteVec] =
    addressInternal.map {
      case Val.Address(LockupScript.P2C(contractId)) => Val.ByteVec(contractId.bytes)
      case addr                                      => throw Compiler.Error(s"Invalid contract address: #@${addr.toBase58}")
    }

  def addressInternal[_: P]: P[Val.Address] =
    P(CharsWhileIn("0-9a-zA-Z")).!.map { input =>
      val lockupScriptOpt = Address.extractLockupScript(input)
      lockupScriptOpt match {
        case Some(lockupScript) => Val.Address(lockupScript)
        case None               => throw Compiler.Error(s"Invalid address: $input")
      }
    }
  def address[_: P]: P[Val.Address] = P("@" ~ addressInternal)

  def bool[_: P]: P[Val.Bool] =
    P(keyword("true") | keyword("false")).!.map {
      case "true" => Val.Bool(true)
      case _      => Val.Bool(false)
    }

  def opByteVecAdd[_: P]: P[Operator] = P("++").map(_ => Concat)
  def opAdd[_: P]: P[Operator]        = P("+").map(_ => Add)
  def opSub[_: P]: P[Operator]        = P("-").map(_ => Sub)
  def opMul[_: P]: P[Operator]        = P("*").map(_ => Mul)
  def opDiv[_: P]: P[Operator]        = P("/").map(_ => Div)
  def opMod[_: P]: P[Operator]        = P("%").map(_ => Mod)
  def opModAdd[_: P]: P[Operator]     = P("⊕" | "`+`").map(_ => ModAdd)
  def opModSub[_: P]: P[Operator]     = P("⊖" | "`-`").map(_ => ModSub)
  def opModMul[_: P]: P[Operator]     = P("⊗" | "`*`").map(_ => ModMul)
  def opSHL[_: P]: P[Operator]        = P("<<").map(_ => SHL)
  def opSHR[_: P]: P[Operator]        = P(">>").map(_ => SHR)
  def opBitAnd[_: P]: P[Operator]     = P("&").map(_ => BitAnd)
  def opXor[_: P]: P[Operator]        = P("^").map(_ => Xor)
  def opBitOr[_: P]: P[Operator]      = P("|").map(_ => BitOr)
  def opEq[_: P]: P[TestOperator]     = P("==").map(_ => Eq)
  def opNe[_: P]: P[TestOperator]     = P("!=").map(_ => Ne)
  def opLt[_: P]: P[TestOperator]     = P("<").map(_ => Lt)
  def opLe[_: P]: P[TestOperator]     = P("<=").map(_ => Le)
  def opGt[_: P]: P[TestOperator]     = P(">").map(_ => Gt)
  def opGe[_: P]: P[TestOperator]     = P(">=").map(_ => Ge)
  def opAnd[_: P]: P[LogicalOperator] = P("&&").map(_ => And)
  def opOr[_: P]: P[LogicalOperator]  = P("||").map(_ => Or)
  def opNot[_: P]: P[LogicalOperator] = P("!").map(_ => Not)

  sealed trait FuncModifier
  case object Pub     extends FuncModifier
  case object Payable extends FuncModifier

  def pub[_: P]: P[FuncModifier]          = keyword("pub").map(_ => Pub)
  def payable[_: P]: P[FuncModifier]      = keyword("payable").map(_ => Payable)
  def funcModifier[_: P]: P[FuncModifier] = P(pub | payable)

  def keywordSet: Set[String] = Set(
    "TxContract",
    "AssetScript",
    "TxScript",
    "let",
    "mut",
    "fn",
    "return",
    "true",
    "false",
    "if",
    "else",
    "while",
    "pub",
    "payable",
    "event",
    "emit",
    "loop",
    "extends"
  )

  val primTpes: Map[String, Type] =
    Type.primitives.map(tpe => (getSimpleName(tpe), tpe)).toArray.toMap
}
