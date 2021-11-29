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

import org.alephium.util.AlephiumSpec

class ArrayTransformerSpec extends AlephiumSpec {
  it should "get sub array" in {
    def varNames(from: Int, to: Int): Seq[Ast.Ident] = {
      (from until to).map(idx => Ast.Ident(s"$idx"))
    }

    // [[[Bool; 5]; 6]; 7]
    val tpe      = Type.FixedSizeArray(Type.FixedSizeArray(Type.FixedSizeArray(Type.Bool, 5), 6), 7)
    val idents   = varNames(0, 5 * 6 * 7)
    val arrayRef = ArrayTransformer.ArrayRef(tpe, idents)
    arrayRef.subArray(Seq.empty) is arrayRef

    val tpe1 = tpe.baseType.asInstanceOf[Type.FixedSizeArray]
    arrayRef.subArray(0) is ArrayTransformer.ArrayRef(tpe1, varNames(0, 30))
    arrayRef.subArray(1) is ArrayTransformer.ArrayRef(tpe1, varNames(30, 60))
    arrayRef.subArray(4) is ArrayTransformer.ArrayRef(tpe1, varNames(120, 150))
    arrayRef.subArray(6) is ArrayTransformer.ArrayRef(tpe1, varNames(180, 210))
    assertThrows[Compiler.Error](arrayRef.subArray(7))

    val tpe2 = tpe1.baseType.asInstanceOf[Type.FixedSizeArray]
    arrayRef.subArray(Seq(0, 0)) is ArrayTransformer.ArrayRef(tpe2, varNames(0, 5))
    arrayRef.subArray(Seq(1, 0)) is ArrayTransformer.ArrayRef(tpe2, varNames(30, 35))
    arrayRef.subArray(Seq(2, 3)) is ArrayTransformer.ArrayRef(tpe2, varNames(75, 80))
    arrayRef.subArray(Seq(6, 5)) is ArrayTransformer.ArrayRef(tpe2, varNames(205, 210))
    assertThrows[Compiler.Error](arrayRef.subArray(Seq(7, 5)))
    assertThrows[Compiler.Error](arrayRef.subArray(Seq(6, 6)))
    assertThrows[Compiler.Error](arrayRef.subArray(Seq(7, 6)))
    assertThrows[Compiler.Error](arrayRef.subArray(Seq(0, 0, 0)))
  }
}
