package org.alephium.protocol.vm.lang

import java.math.BigInteger

import fastparse._
import fastparse.NoWhitespace._

import org.alephium.protocol.vm.Val
import org.alephium.util.{I256, I64, U256, U64}

// scalastyle:off number.of.methods
object Lexer {
  def lowercase[_: P]: P[Unit] = P(CharIn("a-z"))
  def uppercase[_: P]: P[Unit] = P(CharIn("A-Z"))
  def digit[_: P]: P[Unit]     = P(CharIn("0-9"))
  def letter[_: P]: P[Unit]    = P(lowercase | uppercase)
  def newline[_: P]: P[Unit]   = P(NoTrace(StringIn("\r\n", "\n")))

  def ident[_: P]: P[Ast.Ident] =
    P(letter ~ (letter | digit | "_").rep).!.filter(!keywordSet.contains(_)).map(Ast.Ident)
  def callId[_: P]: P[Ast.CallId] = P(ident ~ "!".?.!).map {
    case (id, postfix) => Ast.CallId(id.name, postfix.nonEmpty)
  }

  private[lang] def getSimpleName(obj: Object): String = {
    obj.getClass.getSimpleName.dropRight(1)
  }
  val types: Map[String, Val.Type] =
    Val.Type.types.map(tpe => (getSimpleName(tpe), tpe)).toArray.toMap
  def tpe[_: P]: P[Val.Type] =
    P(ident).filter(id => types.contains(id.name)).map(id => types.apply(id.name))

  def keyword[_: P](s: String): P[Unit] = s ~ !(letter | digit | "_")
  def mut[_: P]: P[Boolean]             = P(keyword("mut").?.!).map(_.nonEmpty)

  def lineComment[_: P]: P[Unit] = P("//" ~ CharsWhile(_ != '\n', 0))
  def emptyChars[_: P]: P[Unit]  = P((CharsWhileIn(" \t\r\n") | lineComment).rep)

  def hexNum[_: P]: P[BigInteger] = P("0x" ~ CharsWhileIn("0-9A-F")).!.map(new BigInteger(_, 16))
  def decNum[_: P]: P[BigInteger] = P(CharsWhileIn("0-9")).!.map(new BigInteger(_))
  def num[_: P]: P[BigInteger]    = negatable(P(hexNum | decNum))
  def negatable[_: P](p: => P[BigInteger]): P[BigInteger] = ("-".?.! ~ p).map {
    case ("-", i) => i.negate()
    case (_, i)   => i
  }
  def typedNum[_: P]: P[Val] =
    P(num ~ ("i" | "u" | "I" | "U" | "b").?.!)
      .filter {
        case (n, "i") => I64.validate(n)
        case (n, "I") => I256.validate(n)
        case (n, "U") => U256.validate(n)
        case (n, "b") => n.signum() >= 0 && n.bitLength() <= 8 // unsigned Byte
        case (n, _)   => U64.validate(n)
      }
      .map {
        case (n, "i") => Val.I64(I64.from(n).get)
        case (n, "I") => Val.I256(I256.from(n).get)
        case (n, "U") => Val.U256(U256.from(n).get)
        case (n, "b") => Val.Byte(n.byteValue())
        case (n, _)   => Val.U64(U64.from(n).get)
      }

  def bool[_: P]: P[Val] = P(keyword("true") | keyword("false")).!.map {
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

  // format: off
  def keywordSet: Set[String] =
    Set("contract", "let", "mut", "fn", "return", "true", "false", "if", "else", "while")
  // format: on
}
