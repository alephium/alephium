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

import org.alephium.crypto.Byte32
import org.alephium.protocol.{ALPH, Hash, PublicKey}
import org.alephium.protocol.model.Address
import org.alephium.protocol.vm.Val
import org.alephium.protocol.vm.lang.ArithOperator._
import org.alephium.util.{AlephiumSpec, Hex, I256, U256}

class LexerSpec extends AlephiumSpec {
  it should "parse lexer" in {
    val byte32  = Byte32.generate.toHexString
    val address = Address.p2pkh(PublicKey.generate)

    fastparse.parse("5", Lexer.typedNum(_)).get.value is Val.U256(U256.unsafe(5))
    fastparse.parse("5u", Lexer.typedNum(_)).get.value is Val.U256(U256.unsafe(5))
    fastparse.parse("5i", Lexer.typedNum(_)).get.value is Val.I256(I256.unsafe(5))
    fastparse.parse("-5i", Lexer.typedNum(_)).get.value is Val.I256(I256.from(-5))
    fastparse.parse("-5", Lexer.typedNum(_)).get.value is Val.I256(I256.from(-5))
    fastparse.parse("0x12", Lexer.typedNum(_)).get.value is Val.U256(U256.unsafe(18))
    fastparse.parse("5e18", Lexer.typedNum(_)).get.value is Val.U256(ALPH.alph(5))
    fastparse.parse("5.12e18", Lexer.typedNum(_)).get.value is Val.U256(ALPH.cent(512))
    fastparse.parse("-5e18", Lexer.typedNum(_)).get.value is Val.I256(
      I256.unsafe(ALPH.alph(5).toBigInt.negate())
    )
    fastparse.parse("-5.12e18", Lexer.typedNum(_)).get.value is Val.I256(
      I256.unsafe(ALPH.cent(512).toBigInt.negate())
    )
    fastparse.parse("1_000_000", Lexer.typedNum(_)).get.value is Val.U256(U256.unsafe(1000000))
    fastparse.parse("1alph", Lexer.typedNum(_)).get.value is Val.U256(ALPH.oneAlph)
    fastparse.parse("1 alph", Lexer.typedNum(_)).get.value is Val.U256(ALPH.oneAlph)
    fastparse.parse("0.01 alph", Lexer.typedNum(_)).get.value is Val.U256(ALPH.cent(1))
    fastparse.parse("1e-18 alph", Lexer.typedNum(_)).get.value is Val.U256(U256.One)

    fastparse.parse(s"#$byte32", Lexer.bytes(_)).get.value is Val.ByteVec(
      Hex.from(byte32).get
    )
    fastparse.parse(s"@${address.toBase58}", Lexer.address(_)).get.value is Val.Address(
      address.lockupScript
    )
    fastparse.parse("x", Lexer.ident(_)).get.value is Ast.Ident("x")
    fastparse.parse("U256", Lexer.typeId(_)).get.value is Ast.TypeId("U256")
    fastparse.parse("Foo", Lexer.typeId(_)).get.value is Ast.TypeId("Foo")
    fastparse.parse("x: U256", StatelessParser.funcArgument(_)).get.value is
      Ast.Argument(Ast.Ident("x"), Type.U256, isMutable = false)
    fastparse.parse("mut x: U256", StatelessParser.funcArgument(_)).get.value is
      Ast.Argument(Ast.Ident("x"), Type.U256, isMutable = true)
    fastparse.parse("// comment", Lexer.lineComment(_)).isSuccess is true
    fastparse.parse("add", Lexer.funcId(_)).get.value is Ast.FuncId("add", false)
    fastparse.parse("add!", Lexer.funcId(_)).get.value is Ast.FuncId("add", true)
  }

  it should "special operators" in {
    fastparse.parse("⊕", Lexer.opModAdd(_)).get.value is ModAdd
    fastparse.parse("⊖", Lexer.opModSub(_)).get.value is ModSub
    fastparse.parse("⊗", Lexer.opModMul(_)).get.value is ModMul
    fastparse.parse("`+`", Lexer.opModAdd(_)).get.value is ModAdd
    fastparse.parse("`-`", Lexer.opModSub(_)).get.value is ModSub
    fastparse.parse("`*`", Lexer.opModMul(_)).get.value is ModMul
    fastparse.parse("++", Lexer.opByteVecAdd(_)).get.value is Concat
  }

  it should "parse bytes and address" in {
    val hash     = Hash.random
    val address  = Address.p2pkh(PublicKey.generate)
    val contract = Address.contract(Hash.random)
    fastparse.parse(s"#${hash.toHexString}", Lexer.bytes(_)).get.value is
      Val.ByteVec(hash.bytes)
    fastparse.parse(s"@${address.toBase58}", Lexer.address(_)).get.value is
      Val.Address(address.lockupScript)
    intercept[Compiler.Error](fastparse.parse(s"#${address.toBase58}", Lexer.bytes(_))) is Compiler
      .Error(s"Invalid byteVec: ${address.toBase58}")
    fastparse.parse(s"#${contract.toBase58}", Lexer.bytes(_)).get.value is
      Val.ByteVec(contract.contractId.bytes)
  }
}
