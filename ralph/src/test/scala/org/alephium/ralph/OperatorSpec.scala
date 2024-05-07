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

import scala.language.implicitConversions

import org.alephium.protocol.config.GroupConfigFixture
import org.alephium.protocol.model.LockupScriptGenerators
import org.alephium.protocol.vm.Val
import org.alephium.util.{AlephiumSpec, Hex, I256, U256}

class OperatorSpec
    extends AlephiumSpec
    with LockupScriptGenerators
    with GroupConfigFixture.Default {
  trait Fixture {
    implicit def toBool(v: Boolean): Val.Bool = Val.Bool(v)

    def operator: Operator
    def test(values: Seq[Val], result: Val) = {
      assume(values.nonEmpty)
      operator.calc(values) is Right(result)
      operator.calc(Seq.empty).leftValue.contains(operator.operatorName) is true
      operator.calc(values :+ values.head).leftValue.contains(operator.operatorName) is true
    }
  }

  it should "test ++" in new Fixture {
    implicit def bytes(hexString: String): Val.ByteVec = Val.ByteVec(Hex.unsafe(hexString))
    val operator                                       = ArithOperator.Concat
    test(Seq("00", "11"), "0011")
  }

  trait I256Fixture extends Fixture {
    implicit def i256(number: Int): Val.I256 = Val.I256(I256.unsafe(number))
  }

  trait U256Fixture extends Fixture {
    implicit def u256(number: Int): Val.U256 = Val.U256(U256.unsafe(number))
  }

  it should "test + (I256)" in new I256Fixture {
    val operator = ArithOperator.Add
    test(Seq(3, 4), 7)
    operator.calc(Seq(Val.I256(I256.MaxValue), 1)) is Left("I256 overflow")
  }

  it should "test - (I256)" in new I256Fixture {
    val operator = ArithOperator.Sub
    test(Seq(3, 4), -1)
    operator.calc(Seq(Val.I256(I256.MinValue), 1)) is Left("I256 overflow")
  }

  it should "test * (I256)" in new I256Fixture {
    val operator = ArithOperator.Mul
    test(Seq(3, 4), 12)
    operator.calc(Seq(Val.I256(I256.MaxValue), 2)) is Left("I256 overflow")
  }

  it should "test / (I256)" in new I256Fixture {
    val operator = ArithOperator.Div
    test(Seq(3, 4), 0)
    operator.calc(Seq(Val.I256(I256.MinValue), -1)) is Left("I256 overflow")
  }

  it should "test % (I256)" in new I256Fixture {
    val operator = ArithOperator.Mod
    test(Seq(3, 4), 3)
    operator.calc(Seq(Val.I256(I256.MinValue), -1)) is Left("I256 overflow")
  }

  it should "test ** (I256)" in new I256Fixture {
    val operator = ArithOperator.Exp
    test(Seq(3, Val.U256(U256.unsafe(4))), 81)
    operator.calc(Seq(Val.I256(I256.MaxValue), Val.U256(U256.Two))) is Left("I256 overflow")
  }

  it should "test + (U256)" in new U256Fixture {
    val operator = ArithOperator.Add
    test(Seq(3, 4), 7)
    operator.calc(Seq(Val.U256(U256.MaxValue), 1)) is Left("U256 overflow")
  }

  it should "test - (U256)" in new U256Fixture {
    val operator = ArithOperator.Sub
    test(Seq(4, 3), 1)
    operator.calc(Seq(Val.U256(U256.MinValue), 1)) is Left("U256 overflow")
  }

  it should "test * (U256)" in new U256Fixture {
    val operator = ArithOperator.Mul
    test(Seq(3, 4), 12)
    operator.calc(Seq(Val.U256(U256.MaxValue), 2)) is Left("U256 overflow")
  }

  it should "test / (U256)" in new U256Fixture {
    val operator = ArithOperator.Div
    test(Seq(3, 4), 0)
    operator.calc(Seq(Val.U256(U256.MinValue), 0)) is Left("U256 overflow")
  }

  it should "test % (U256)" in new U256Fixture {
    val operator = ArithOperator.Mod
    test(Seq(3, 4), 3)
    operator.calc(Seq(Val.U256(U256.MinValue), 0)) is Left("U256 overflow")
  }

  it should "test ** (U256)" in new U256Fixture {
    val operator = ArithOperator.Exp
    test(Seq(3, 4), 81)
    operator.calc(Seq(Val.U256(U256.MaxValue), 2)) is Left("U256 overflow")
  }

  it should "test |+|" in new U256Fixture {
    val operator = ArithOperator.ModAdd
    test(Seq(3, 4), 7)
    test(Seq.fill(2)(Val.U256(U256.MaxValue)), Val.U256(U256.MaxValue.subOneUnsafe()))
  }

  it should "test |-|" in new U256Fixture {
    val operator = ArithOperator.ModSub
    test(Seq(4, 3), 1)
    test(Seq(0, 1), Val.U256(U256.MaxValue))
  }

  it should "test |*|" in new U256Fixture {
    val operator = ArithOperator.ModMul
    test(Seq(3, 4), 12)
    test(Seq(Val.U256(U256.MaxValue), 2), Val.U256(U256.MaxValue.subOneUnsafe()))
  }

  it should "test |**|" in new U256Fixture {
    val operator = ArithOperator.ModExp
    test(Seq(3, 4), 81)
    test(Seq(2, 256), 0)
  }

  it should "test <<" in new U256Fixture {
    val operator = ArithOperator.SHL
    test(Seq(0xff, 4), 0xff0)
  }

  it should "test >>" in new U256Fixture {
    val operator = ArithOperator.SHR
    test(Seq(0xff, 4), 0x0f)
  }

  it should "test &" in new U256Fixture {
    val operator = ArithOperator.BitAnd
    test(Seq(0xff, 0xf0), 0xf0)
  }

  it should "test |" in new U256Fixture {
    val operator = ArithOperator.BitOr
    test(Seq(0xff, 0xf0), 0xff)
  }

  it should "test ^" in new U256Fixture {
    val operator = ArithOperator.Xor
    test(Seq(0xff, 0xf0), 0x0f)
  }

  it should "test ==" in new Fixture {
    val operator = TestOperator.Eq
    val value0   = vmValGen.sample.get
    val value1   = vmValGen.sample.get
    test(Seq(value0, value0), true)
    test(Seq(value0, value1), value0 == value1)
  }

  it should "test !=" in new Fixture {
    val operator = TestOperator.Ne
    val value0   = vmValGen.sample.get
    val value1   = vmValGen.sample.get
    test(Seq(value0, value0), false)
    test(Seq(value0, value1), value0 != value1)
  }

  it should "test < (I256)" in new I256Fixture {
    val operator = TestOperator.Lt
    test(Seq(3, 4), true)
    test(Seq(3, 3), false)
    test(Seq(4, 3), false)
  }

  it should "test <= (I256)" in new I256Fixture {
    val operator = TestOperator.Le
    test(Seq(3, 4), true)
    test(Seq(3, 3), true)
    test(Seq(4, 3), false)
  }

  it should "test > (I256)" in new I256Fixture {
    val operator = TestOperator.Gt
    test(Seq(3, 4), false)
    test(Seq(3, 3), false)
    test(Seq(4, 3), true)
  }

  it should "test >= (I256)" in new I256Fixture {
    val operator = TestOperator.Ge
    test(Seq(3, 4), false)
    test(Seq(3, 3), true)
    test(Seq(4, 3), true)
  }

  it should "test < (U256)" in new U256Fixture {
    val operator = TestOperator.Lt
    test(Seq(3, 4), true)
    test(Seq(3, 3), false)
    test(Seq(4, 3), false)
  }

  it should "test <= (U256)" in new U256Fixture {
    val operator = TestOperator.Le
    test(Seq(3, 4), true)
    test(Seq(3, 3), true)
    test(Seq(4, 3), false)
  }

  it should "test > (U256)" in new U256Fixture {
    val operator = TestOperator.Gt
    test(Seq(3, 4), false)
    test(Seq(3, 3), false)
    test(Seq(4, 3), true)
  }

  it should "test >= (U256)" in new U256Fixture {
    val operator = TestOperator.Ge
    test(Seq(3, 4), false)
    test(Seq(3, 3), true)
    test(Seq(4, 3), true)
  }

  it should "test !" in new Fixture {
    val operator = LogicalOperator.Not
    test(Seq(false), true)
    test(Seq(true), false)
  }

  it should "test &&" in new Fixture {
    val operator = LogicalOperator.And
    test(Seq(false, false), false)
    test(Seq(false, true), false)
    test(Seq(true, false), false)
    test(Seq(true, true), true)
  }

  it should "test ||" in new Fixture {
    val operator = LogicalOperator.Or
    test(Seq(false, false), false)
    test(Seq(false, true), true)
    test(Seq(true, false), true)
    test(Seq(true, true), true)
  }
}
