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

import scala.collection.mutable

final case class ScopeState(
    var varIndex: Int,
    var freshNameIndex: Int,
    var arrayIndexVar: Option[Ast.Ident]
)

object ScopeState {
  def default(): ScopeState = ScopeState(0, 0, None)
}

trait Scope { self: Compiler.State[_] =>
  val scopes                        = mutable.Map.empty[Ast.FuncId, ScopeState]
  var currentScope: Ast.FuncId      = Ast.FuncId.empty
  var currentScopeState: ScopeState = ScopeState.default()
  val currentScopeUsedVars          = mutable.Set.empty[String]

  def setFuncScope(funcId: Ast.FuncId): Unit = {
    currentScopeUsedVars.clear()
    scopes.get(funcId) match {
      case Some(scopeState) =>
        currentScope = funcId
        currentScopeState = scopeState
      case None =>
        currentScope = funcId
        currentScopeState = ScopeState.default()
        scopes += currentScope -> currentScopeState
    }
  }

  @inline final def freshName(): String = {
    val name = s"_${currentScope.name}_gen#${currentScopeState.freshNameIndex}"
    currentScopeState.freshNameIndex += 1
    name
  }

  def getArrayIndexVar(addLocalVariable: Ast.Ident => Unit): Ast.Ident = {
    currentScopeState.arrayIndexVar match {
      case Some(ident) => ident
      case None =>
        val ident = Ast.Ident(freshName())
        addLocalVariable(ident)
        currentScopeState.arrayIndexVar = Some(ident)
        ident
    }
  }
}
