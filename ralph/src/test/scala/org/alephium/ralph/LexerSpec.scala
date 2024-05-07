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

import fastparse.Parsed

import org.alephium.crypto.Byte32
import org.alephium.protocol.{ALPH, Hash, PublicKey}
import org.alephium.protocol.model.{Address, ContractId}
import org.alephium.protocol.vm.Val
import org.alephium.ralph.ArithOperator._
import org.alephium.ralph.error.CompilerError
import org.alephium.util.{AlephiumSpec, Hex, I256, U256}

abstract class LexerSpec(fileURI: Option[java.net.URI]) extends AlephiumSpec {
  val Lexer           = new Lexer(fileURI)
  val StatelessParser = new StatelessParser(fileURI)

  def parsePositioned[A <: Ast.Positioned](
      input: String,
      parser: fastparse.P[_] => fastparse.P[A]
  ): fastparse.Parsed[A] = {
    val result      = fastparse.parse(input, parser(_))
    val sourceIndex = result.get.value.sourceIndex.get
    sourceIndex.width is input.length
    sourceIndex.fileURI is fileURI
    result
  }

  it should "parse lexer" in {
    val byte32  = Byte32.generate.toHexString
    val address = Address.p2pkh(PublicKey.generate)

    parsePositioned("5", Lexer.typedNum(_)).get.value.v is Val.U256(U256.unsafe(5))
    parsePositioned("5u", Lexer.typedNum(_)).get.value.v is Val.U256(U256.unsafe(5))
    parsePositioned("5i", Lexer.typedNum(_)).get.value.v is Val.I256(I256.unsafe(5))
    parsePositioned("-5i", Lexer.typedNum(_)).get.value.v is Val.I256(I256.from(-5))
    parsePositioned("-5", Lexer.typedNum(_)).get.value.v is Val.I256(I256.from(-5))
    parsePositioned("0x12", Lexer.typedNum(_)).get.value.v is Val.U256(U256.unsafe(18))
    parsePositioned("5e18", Lexer.typedNum(_)).get.value.v is Val.U256(ALPH.alph(5))
    parsePositioned("5.12e18", Lexer.typedNum(_)).get.value.v is Val.U256(ALPH.cent(512))
    parsePositioned("-5e18", Lexer.typedNum(_)).get.value.v is Val.I256(
      I256.unsafe(ALPH.alph(5).toBigInt.negate())
    )
    parsePositioned("-5.12e18", Lexer.typedNum(_)).get.value.v is Val.I256(
      I256.unsafe(ALPH.cent(512).toBigInt.negate())
    )
    parsePositioned("1_000_000", Lexer.typedNum(_)).get.value.v is Val.U256(U256.unsafe(1000000))
    parsePositioned("1alph", Lexer.typedNum(_)).get.value.v is Val.U256(ALPH.oneAlph)
    parsePositioned("1 alph", Lexer.typedNum(_)).get.value.v is Val.U256(ALPH.oneAlph)
    parsePositioned("0.01 alph", Lexer.typedNum(_)).get.value.v is Val.U256(ALPH.cent(1))
    parsePositioned("1e-18 alph", Lexer.typedNum(_)).get.value.v is Val.U256(U256.One)
    parsePositioned("1e-18 alph", Lexer.typedNum(_)).get.value.v is Val.U256(U256.One)

    parsePositioned(s"#$byte32", Lexer.bytes(_)).get.value.v is Val.ByteVec(
      Hex.from(byte32).get
    )
    parsePositioned(s"@${address.toBase58}", Lexer.address(_)).get.value.v is Val.Address(
      address.lockupScript
    )
    parsePositioned("x", Lexer.ident(_)).get.value is Ast.Ident("x")
    parsePositioned("U256", Lexer.typeId(_)).get.value is Ast.TypeId("U256")
    parsePositioned("Foo", Lexer.typeId(_)).get.value is Ast.TypeId("Foo")
    parsePositioned("x: U256", StatelessParser.funcArgument(_)).get.value is
      Ast.Argument(Ast.Ident("x"), Type.U256, isMutable = false, isUnused = false)
    parsePositioned("mut x: U256", StatelessParser.funcArgument(_)).get.value is
      Ast.Argument(Ast.Ident("x"), Type.U256, isMutable = true, isUnused = false)
    parsePositioned("@unused mut x: U256", StatelessParser.funcArgument(_)).get.value is
      Ast.Argument(Ast.Ident("x"), Type.U256, isMutable = true, isUnused = true)
    fastparse
      .parse("@unused mut x: U256", StatelessParser.contractField(allowMutable = true)(_))
      .get
      .value is
      Ast.Argument(Ast.Ident("x"), Type.U256, isMutable = true, isUnused = true)
    fastparse.parse("// comment", Lexer.lineComment(_)).isSuccess is true
    parsePositioned("add", Lexer.funcId(_)).get.value is Ast.FuncId("add", false)
    parsePositioned("add!", Lexer.funcId(_)).get.value is Ast.FuncId("add", true)
  }

  it should "report CompilerError messages with line number information" in {
    {
      info("when input is not a number type")
      val input   = "a"
      val failure = fastparse.parse(input, Lexer.typedNum(_)).asInstanceOf[Parsed.Failure].trace()

      failure.index is 0
      failure.longMsg is s"""Expected an I256 or U256 value:1:1 / num:1:1 / (hexNum | integer):1:1, found "$input""""
    }

    {
      info("when input is an invalid U256")
      val input = "123456789" * 10
      val failure =
        intercept[CompilerError.`Expected an U256 value`](parsePositioned(input, Lexer.typedNum(_)))

      failure.format(input) is
        """-- error (1:1): Syntax error
          |1 |123456789123456789123456789123456789123456789123456789123456789123456789123456789123456789
          |  |^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^
          |  |Expected an U256 value
          |""".stripMargin
    }

    {
      info("when input is an invalid negative I256")
      val input = "-" + ("123456789" * 10)
      val failure =
        intercept[CompilerError.`Expected an I256 value`](parsePositioned(input, Lexer.typedNum(_)))

      failure.format(input) is
        """-- error (1:1): Syntax error
          |1 |-123456789123456789123456789123456789123456789123456789123456789123456789123456789123456789
          |  |^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^
          |  |Expected an I256 value
          |""".stripMargin
    }

    {
      info("when input is an invalid I256")
      val input = ("123456789" * 10) + "i"
      val failure =
        intercept[CompilerError.`Expected an I256 value`](parsePositioned(input, Lexer.typedNum(_)))

      failure.format(input) is
        """-- error (1:1): Syntax error
          |1 |123456789123456789123456789123456789123456789123456789123456789123456789123456789123456789i
          |  |^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^
          |  |Expected an I256 value
          |""".stripMargin
    }

    {

      def errorAssetScript(errorTypedNum: String): String =
        s"""
           |// comment
           |AssetScript Foo {
           |  pub fn bar(a: U256, b: U256) -> (U256) {
           |    let c = $errorTypedNum
           |    return (a + b + c)
           |  }
           |}
           |""".stripMargin

      {
        info("when the error is in a larger ralph program")

        val invalidTypedNum = "123456789" * 10
        val errorScript     = errorAssetScript(invalidTypedNum)

        val failure =
          intercept[CompilerError.`Expected an U256 value`] {
            parsePositioned(errorScript, StatelessParser.assetScript(_))
          }

        failure.format(errorScript) is
          """-- error (5:13): Syntax error
            |5 |    let c = 123456789123456789123456789123456789123456789123456789123456789123456789123456789123456789
            |  |            ^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^
            |  |            Expected an U256 value
            |""".stripMargin
      }

    }
  }

  it should "special operators" in {
    fastparse.parse("⊕", Lexer.opModAdd(_)).get.value is ModAdd
    fastparse.parse("⊖", Lexer.opModSub(_)).get.value is ModSub
    fastparse.parse("⊗", Lexer.opModMul(_)).get.value is ModMul
    fastparse.parse("|+|", Lexer.opModAdd(_)).get.value is ModAdd
    fastparse.parse("|-|", Lexer.opModSub(_)).get.value is ModSub
    fastparse.parse("|*|", Lexer.opModMul(_)).get.value is ModMul
    fastparse.parse("++", Lexer.opByteVecAdd(_)).get.value is Concat
  }

  it should "parse bytes and address" in {
    val hash     = Hash.random
    val address  = Address.p2pkh(PublicKey.generate)
    val contract = Address.contract(ContractId.random)
    parsePositioned(s"#${hash.toHexString}", Lexer.bytes(_)).get.value.v is
      Val.ByteVec(hash.bytes)
    parsePositioned(s"@${address.toBase58}", Lexer.address(_)).get.value.v is
      Val.Address(address.lockupScript)
    intercept[CompilerError.`Invalid byteVec`](
      parsePositioned(s"#${address.toBase58}", Lexer.bytes(_))
    ) is CompilerError.`Invalid byteVec`(address.toBase58, 1, fileURI)
    parsePositioned(s"#${contract.toBase58}", Lexer.bytes(_)).get.value.v is
      Val.ByteVec(contract.contractId.bytes)

    {
      info("format invalid byteVec")

      val invalidByteVec = "#12DRq8VCM7kTs7eDjGyvKWuqJVbYS6DysC3ttguLabGD2"
      intercept[CompilerError.`Invalid byteVec`](parsePositioned(invalidByteVec, Lexer.bytes(_)))
        .format(invalidByteVec) is
        """-- error (1:2): Type error
          |1 |#12DRq8VCM7kTs7eDjGyvKWuqJVbYS6DysC3ttguLabGD2
          |  | ^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^
          |  | Invalid byteVec
          |""".stripMargin
    }
  }

  it should "parse string" in {
    fastparse.parse("", Lexer.stringPart(_)).get.value is ""
    fastparse.parse("a", Lexer.stringPart(_)).get.value is "a"
    fastparse.parse(" ", Lexer.stringPart(_)).get.value is " "
    fastparse.parse("a b c$", Lexer.stringPart(_)).get.value is "a b c"
    fastparse.parse("a b c$$$`", Lexer.stringPart(_)).get.value is "a b c$`"
    fastparse.parse("a b c$x$$`", Lexer.stringPart(_)).get.value is "a b c"
    fastparse.parse("$", Lexer.stringPart(_)).get.value is ""
    fastparse.parse("`", Lexer.stringPart(_)).get.value is ""
  }

  it should "parse mut declarations" in {
    {
      info("fail mut declarations when mutability is disallowed")
      val code = s"mut foo"

      // when allowMutable is false, it should not let `mut` declarations through.
      val error =
        intercept[CompilerError.`Expected an immutable variable`] {
          fastparse
            .parse(code, Lexer.mutMaybe(allowMutable = false)(_))
        }

      error.format(code) is
        """-- error (1:1): Syntax error
          |1 |mut foo
          |  |^^^
          |  |Expected an immutable variable
          |""".stripMargin
    }

    {
      info("succeed mut declarations when mutability is allowed")
      forAll { right: String =>
        // when mut has an identifier.
        fastparse
          .parse(s"mut $right", Lexer.mutMaybe(allowMutable = true)(_))
          .asInstanceOf[Parsed.Success[Boolean]]
          .value is true
      }

      // when mut does not have an identifier.
      fastparse
        .parse(s"mut", Lexer.mutMaybe(allowMutable = true)(_))
        .asInstanceOf[Parsed.Success[Boolean]]
        .value is true
    }

    {
      info("succeed for immutable declarations")
      forAll { right: String =>
        // immutable declarations should always be allowed.
        fastparse
          .parse(s"$right", Lexer.mutMaybe(allowMutable = true)(_))
          .asInstanceOf[Parsed.Success[Boolean]]
          .value is false

        fastparse
          .parse(s"$right", Lexer.mutMaybe(allowMutable = false)(_))
          .asInstanceOf[Parsed.Success[Boolean]]
          .value is false
      }
    }
  }

  it should "report invalid decimal number" in {
    val number = "0.1"

    val error =
      intercept[CompilerError.`Invalid number`](fastparse.parse(number, Lexer.integer(_)))

    error is CompilerError.`Invalid number`(number, 0, fileURI)

    error
      .format(number) is
      """-- error (1:1): Type error
        |1 |0.1
        |  |^^^
        |  |Invalid number
        |""".stripMargin
  }

  it should "report invalid address" in {
    val contractAddress = "abcefgh"

    val error =
      intercept[CompilerError.`Invalid address`] {
        fastparse.parse(contractAddress, Lexer.contractAddress(_))
      }

    error is CompilerError.`Invalid address`(contractAddress, 0, fileURI)

    error.format(contractAddress) is
      """-- error (1:1): Type error
        |1 |abcefgh
        |  |^^^^^^^
        |  |Invalid address
        |""".stripMargin
  }
}

class LexerNoFileSpec extends LexerSpec(None)
class LexerFileSpec   extends LexerSpec(Some(new java.net.URI("file:///path/to/file")))
