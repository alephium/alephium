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

import org.alephium.util.{AlephiumSpec, AVector}

class FrameSpec extends AlephiumSpec with FrameFixture {
  it should "initialize frame and use operand stack for method args" in {
    val frame = genStatelessFrame()
    frame.opStack.offset is 3
    frame.opStack.capacity is 0
    frame.opStack.currentIndex is 3
  }

  it should "create new frame and use operand stack for method args" in {
    val frame = genStatefulFrame()
    frame.opStack.offset is 2
    frame.opStack.capacity is 8
    frame.opStack.currentIndex is 2
  }
}

trait FrameFixture extends ContextGenerators {
  def baseMethod[Ctx <: StatelessContext](localsLength: Int) = Method[Ctx](
    isPublic = true,
    isPayable = false,
    argsLength = localsLength - 1,
    localsLength,
    returnLength = 0,
    instrs = AVector.empty
  )

  def genStatelessFrame(): Frame[StatelessContext] = {
    val method         = baseMethod[StatelessContext](2)
    val script         = StatelessScript.unsafe(AVector(method))
    val (obj, context) = prepareStatelessScript(script)
    Frame
      .stateless(
        context,
        obj,
        method,
        Stack.unsafe(AVector[Val](Val.True, Val.True), 3),
        _ => okay
      )
      .rightValue
  }

  def genStatefulFrame(): Frame[StatefulContext] = {
    val method         = baseMethod[StatefulContext](2)
    val script         = StatefulScript.unsafe(AVector(method))
    val (obj, context) = prepareStatefulScript(script)
    Frame
      .stateful(
        context,
        None,
        None,
        obj,
        method,
        AVector(Val.True),
        Stack.ofCapacity(10),
        _ => okay
      )
      .rightValue
  }
}
