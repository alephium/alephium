package org.alephium.protocol.vm.lang

import java.math.BigInteger

import fastparse._
import fastparse.NoWhitespace._

import org.alephium.protocol.vm.Val
import org.alephium.util.{I256, I64, U256, U64}

object Lexer {
  def lowercase[_: P]: P[Unit] = P(CharIn("a-z"))
  def uppercase[_: P]: P[Unit] = P(CharIn("A-Z"))
  def digit[_: P]: P[Unit]     = P(CharIn("0-9"))
  def letter[_: P]: P[Unit]    = P(lowercase | uppercase)
  def newline[_: P]: P[Unit]   = P(NoTrace(StringIn("\r\n", "\n")))

  def ident[_: P]: P[Ast.Ident] =
    P(letter ~ (letter | digit | "_").rep).!.filter(!keywordSet.contains(_)).map(Ast.Ident)

  private[lang] def getSimpleName(obj: Object): String = {
    obj.getClass.getSimpleName.dropRight(1)
  }
  val types: Map[String, Val.Type] =
    Val.Type.types.map(tpe => (getSimpleName(tpe), tpe)).toArray.toMap
  def tpe[_: P]: P[Val.Type] =
    P(ident).filter(id => types.contains(id.name)).map(id => types.apply(id.name))

  def keyword[_: P](s: String): P[Unit] = s ~ !(letter | digit | "_")
  def bool[_: P]: P[Unit]               = P(keyword("true") | keyword("false"))

  def lineComment[_: P]: P[Unit] = {
    def noEndChar1 = P(CharsWhile(c => c != '\n' && c != '\r'))
    def noEndChar2 = P(!newline ~ AnyChar)
    P("//" ~ (noEndChar1 | noEndChar2).rep ~ &(newline | End))
  }

  def hexNum[_: P]: P[BigInteger] = P("0x" ~ CharsWhileIn("0-9A-F")).!.map(new BigInteger(_, 16))
  def decNum[_: P]: P[BigInteger] = P(CharsWhileIn("0-9")).!.map(new BigInteger(_))
  def num[_: P]: P[BigInteger]    = negatable(P(hexNum | decNum))
  def negatable[_: P](p: => P[BigInteger]): P[BigInteger] = ("-".?.! ~ p).map {
    case ("-", i) => i.negate()
    case (_, i)   => i
  }
  def typedNum[_: P]: P[Val] = P(num ~ ("i" | "u" | "I" | "U").?.!).map {
    case (n, "i") => Val.I64(I64.from(n).get)
    case (n, "I") => Val.I256(I256.from(n).get)
    case (n, "U") => Val.U256(U256.from(n).get)
    case (n, _)   => Val.U64(U64.from(n).get)
  }

  def operator[_: P]: P[Ast.Operator] = P("+" | "-" | "*" | "/").!.map {
    case "+" => Ast.Add
    case "-" => Ast.Sub
    case "*" => Ast.Mul
    case "/" => Ast.Div
    case "%" => Ast.Mod
  }

  // format: off
  def keywordSet: Set[String] =
    Set("val", "var", "def", "return", "if", "else", "for", "=", ":", "+", "-", "*", "/", "%", "true", "false")
  // format: on
}
