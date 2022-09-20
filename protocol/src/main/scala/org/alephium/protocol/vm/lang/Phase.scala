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

import scala.collection.mutable

sealed trait Phase

object Phase {
  case object Initial extends Phase
  case object Check   extends Phase
  case object GenCode extends Phase
}

trait PhaseLike { self: Compiler.State[_] =>
  var phase: Phase         = Phase.Initial
  val genCodePhaseNewVars  = mutable.Set.empty[String]
  val checkPhaseVarIndexes = mutable.Map.empty[Ast.FuncId, Int]

  def setCheckPhase(): Unit = {
    assume(phase == Phase.Initial || phase == Phase.Check)
    phase = Phase.Check
  }

  def setGenCodePhase(): Unit = {
    phase match {
      case Phase.Check   => setFirstGenCodePhase()
      case Phase.GenCode => resetForGenCode()
      case _ => throw new RuntimeException(s"Invalid compiler phase switch: $phase -> GenCode")
    }
  }

  private def setFirstGenCodePhase(): Unit = {
    assume(phase == Phase.Check)
    assume(checkPhaseVarIndexes.isEmpty)
    phase = Phase.GenCode
    scopes.foreach { case (scopeId, scopeState) =>
      checkPhaseVarIndexes += scopeId -> scopeState.varIndex
    }
  }

  private def resetForGenCode(): Unit = {
    assume(phase == Phase.GenCode)
    phase = Phase.Check

    varTable --= genCodePhaseNewVars
    scopes.foreach { case (scopeId, scopeState) =>
      scopeState.varIndex = checkPhaseVarIndexes(scopeId)
      scopeState.freshNameIndex = 0
      scopeState.arrayIndexVar = None
    }
  }

  def trackGenCodePhaseNewVars(name: String): Unit = {
    if (phase == Phase.GenCode) {
      genCodePhaseNewVars += name
    }
  }
}
