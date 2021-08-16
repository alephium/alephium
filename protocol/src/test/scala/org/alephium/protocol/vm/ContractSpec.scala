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

package org.alephium.protocol.vm

import org.scalatest.Assertion

import org.alephium.serde._
import org.alephium.util.{AlephiumSpec, AVector}

class ContractSpec extends AlephiumSpec {
  trait ScriptFixture[Ctx <: StatelessContext] {
    val method = Method[Ctx](
      isPublic = true,
      isPayable = false,
      argsLength = 0,
      localsLength = 0,
      returnLength = 0,
      instrs = AVector.empty
    )

    def pass0(method: Method[Ctx]): Assertion = {
      pass1(AVector(method))
    }

    def pass1(methods: AVector[Method[Ctx]]): Assertion

    def fail0(method: Method[Ctx]): Assertion = {
      fail1(AVector(method))
    }

    def fail1(methods: AVector[Method[Ctx]]): Assertion
  }

  it should "validate stateless scripts" in new ScriptFixture[StatelessContext] {
    def pass1(methods: AVector[Method[StatelessContext]]): Assertion = {
      val script = StatelessScript.unsafe(methods)
      deserialize[StatelessScript](serialize(script)) isE script
    }
    def fail1(methods: AVector[Method[StatelessContext]]): Assertion = {
      val script = StatelessScript.unsafe(methods)
      deserialize[StatelessScript](serialize(script)).leftValue is a[SerdeError.Validation]
    }

    pass0(method)
    pass1(AVector(method, method))
    fail1(AVector.empty)
    fail0(method.copy(isPublic = false))
    fail0(method.copy(isPayable = true))
    fail0(method.copy(argsLength = -1))
    fail0(method.copy(localsLength = -1))
    fail0(method.copy(returnLength = -1))
    fail0(method.copy(argsLength = 1, localsLength = 0))
    pass1(AVector(method, method.copy(isPublic = false)))
    fail1(AVector(method, method.copy(isPayable = true)))
    fail1(AVector(method, method.copy(argsLength = -1)))
    fail1(AVector(method, method.copy(localsLength = -1)))
    fail1(AVector(method, method.copy(returnLength = -1)))
    fail1(AVector(method, method.copy(argsLength = 1, localsLength = 0)))
  }

  it should "validate stateful scripts" in new ScriptFixture[StatefulContext] {
    def pass1(methods: AVector[Method[StatefulContext]]): Assertion = {
      val script = StatefulScript.unsafe(methods)
      deserialize[StatefulScript](serialize(script)) isE script
    }
    def fail1(methods: AVector[Method[StatefulContext]]): Assertion = {
      val script = StatefulScript.unsafe(methods)
      deserialize[StatefulScript](serialize(script)).leftValue is a[SerdeError.Validation]
    }

    pass0(method)
    pass1(AVector(method, method))
    fail1(AVector.empty)
    fail0(method.copy(isPublic = false))
    fail0(method.copy(argsLength = -1))
    fail0(method.copy(localsLength = -1))
    fail0(method.copy(returnLength = -1))
    fail0(method.copy(argsLength = 1, localsLength = 0))
    pass1(AVector(method, method.copy(isPublic = false)))
    fail1(AVector(method, method.copy(argsLength = -1)))
    fail1(AVector(method, method.copy(localsLength = -1)))
    fail1(AVector(method, method.copy(returnLength = -1)))
    fail1(AVector(method, method.copy(argsLength = 1, localsLength = 0)))
  }

  it should "not validate empty scripts" in {
    val contract0 = StatefulContract(0, AVector.empty)
    StatefulContract.check(contract0).leftValue isE EmptyMethods
    val contract1 = StatefulContract(-1, AVector.empty)
    StatefulContract.check(contract1).leftValue isE InvalidFieldLength

    val method = Method[StatefulContext](
      isPublic = true,
      isPayable = false,
      argsLength = 0,
      localsLength = 0,
      returnLength = 0,
      instrs = AVector.empty
    )
    val contract2 = StatefulContract(0, AVector(method))
    StatefulContract.check(contract2) isE ()
    val contract3 = StatefulContract(0, AVector(method.copy(argsLength = -1)))
    StatefulContract.check(contract3).leftValue isE InvalidMethod
    val contract4 = StatefulContract(0, AVector(method.copy(localsLength = -1)))
    StatefulContract.check(contract4).leftValue isE InvalidMethod
    val contract5 = StatefulContract(0, AVector(method.copy(returnLength = -1)))
    StatefulContract.check(contract5).leftValue isE InvalidMethod
    val contract6 = StatefulContract(0, AVector(method, method.copy(argsLength = -1)))
    StatefulContract.check(contract6).leftValue isE InvalidMethod
    val contract7 = StatefulContract(0, AVector(method, method.copy(localsLength = -1)))
    StatefulContract.check(contract7).leftValue isE InvalidMethod
    val contract8 = StatefulContract(0, AVector(method, method.copy(returnLength = -1)))
    StatefulContract.check(contract8).leftValue isE InvalidMethod
    val contract9 = StatefulContract(0, AVector(method, method))
    StatefulContract.check(contract9) isE ()
    val contract10 = StatefulContract(0, AVector(method.copy(argsLength = 1, localsLength = 0)))
    StatefulContract.check(contract10).leftValue isE InvalidMethod
    val contract11 =
      StatefulContract(0, AVector(method, method.copy(argsLength = 1, localsLength = 0)))
    StatefulContract.check(contract11).leftValue isE InvalidMethod
  }
}
