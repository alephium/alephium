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

import org.alephium.protocol.vm.{StatelessContext, Val}
import org.alephium.util.{AlephiumSpec, U256}

class ConstantsSpec extends AlephiumSpec {
  import org.alephium.ralph.Ast._

  trait Fixture {
    val constants = Constants.lazyEvaluated[StatelessContext]

    def circularReferenceError(ident: Ident) = {
      intercept[Compiler.Error](constants.getConstantValue(ident)).message is
        s"Found circular reference when evaluating constant ${ident.name}"
    }
  }

  it should "report an error if the constant does not exist" in new Fixture {
    constants.addConstants(Seq(ConstantVarDef(Ident("A"), Const(Val.False))))
    constants.addEnums(Seq(EnumDef(TypeId("E"), Seq(EnumField(Ident("A"), Const(Val.True))))))
    constants.getConstantValue(Ident("A")) is Val.False
    constants.getConstantValue(Ident("E.A")) is Val.True
    intercept[Compiler.Error](constants.getConstantValue(Ident("B"))).message is
      "Constant variable B does not exist"
  }

  it should "report an error if there is a circular reference" in new Fixture {
    constants.addConstants(Seq(ConstantVarDef(Ident("A"), Variable(Ident("A")))))
    circularReferenceError(Ident("A"))

    constants.addConstants(
      Seq(
        ConstantVarDef(Ident("A"), Variable(Ident("B"))),
        ConstantVarDef(Ident("B"), Variable(Ident("A")))
      )
    )
    circularReferenceError(Ident("A"))
    circularReferenceError(Ident("B"))

    constants.addConstants(
      Seq(
        ConstantVarDef(Ident("A"), Variable(Ident("B"))),
        ConstantVarDef(Ident("B"), Variable(Ident("C"))),
        ConstantVarDef(
          Ident("C"),
          Binop(ArithOperator.Add, Variable(Ident("A")), Const(Val.U256(U256.One)))
        )
      )
    )
    circularReferenceError(Ident("A"))
    circularReferenceError(Ident("B"))
    circularReferenceError(Ident("C"))
  }

  it should "calculate and cache calculated constants" in new Fixture {
    constants.addConstants(
      Seq(
        ConstantVarDef(Ident("A"), Const(Val.U256(U256.One))),
        ConstantVarDef(Ident("B"), Const(Val.U256(U256.Two))),
        ConstantVarDef(
          Ident("C"),
          Binop(ArithOperator.Add, Variable(Ident("A")), Variable(Ident("B")))
        )
      )
    )

    val cached = constants.constants(Ident("C"))
    cached.calculated is None
    cached.isCalculating is false

    val value = constants.getConstantValue(Ident("C"))
    value is Val.U256(U256.unsafe(3))
    cached.isCalculating is false
    cached.calculated is Some(value)
  }
}
