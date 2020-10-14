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
import org.alephium.protocol.vm.Val
import org.alephium.util.{AlephiumSpec, Hex, I256, I64, U256, U64}

class LexerSpec extends AlephiumSpec {
  it should "parse lexer" in {
    val byte32 = Byte32.generate.toHexString

    fastparse.parse("5", Lexer.typedNum(_)).get.value is Val.U64(U64.unsafe(5))
    fastparse.parse("-5i", Lexer.typedNum(_)).get.value is Val.I64(I64.from(-5))
    fastparse.parse("5U", Lexer.typedNum(_)).get.value is Val.U256(U256.unsafe(5))
    fastparse.parse("-5I", Lexer.typedNum(_)).get.value is Val.I256(I256.from(-5))
    fastparse.parse(s"#$byte32", Lexer.bytes(_)).get.value is Val.ByteVec(
      Hex.asArraySeq(byte32).get)
    fastparse.parse("x", Lexer.ident(_)).get.value is Ast.Ident("x")
    fastparse.parse("U64", Lexer.typeId(_)).get.value is Ast.TypeId("U64")
    fastparse.parse("Foo", Lexer.typeId(_)).get.value is Ast.TypeId("Foo")
    fastparse.parse("x: U64", StatelessParser.funcArgument(_)).get.value is
      Ast.Argument(Ast.Ident("x"), Type.U64, isMutable = false)
    fastparse.parse("mut x: U64", StatelessParser.funcArgument(_)).get.value is
      Ast.Argument(Ast.Ident("x"), Type.U64, isMutable = true)
    fastparse.parse("// comment", Lexer.lineComment(_)).isSuccess is true
    fastparse.parse("add", Lexer.funcId(_)).get.value is Ast.FuncId("add", false)
    fastparse.parse("add!", Lexer.funcId(_)).get.value is Ast.FuncId("add", true)
  }
}
