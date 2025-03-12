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

import scala.annotation.tailrec

sealed trait VariableScope {
  def include(childScope: VariableScope): Boolean
  def getScopeRefPath: Seq[Ast.Positioned]
}
case object FunctionRoot extends VariableScope {
  def include(childScope: VariableScope): Boolean = true
  def getScopeRefPath: Seq[Ast.Positioned]        = Seq.empty
}
final case class ChildScope(
    parent: VariableScope,
    scopeRef: Ast.Positioned,
    sourceIndex: Option[SourceIndex],
    depth: Int
) extends VariableScope {
  @tailrec
  def include(another: VariableScope): Boolean = {
    another match {
      case FunctionRoot => false
      case another: ChildScope =>
        if (another.depth < depth) {
          false
        } else if (another.scopeRef eq this.scopeRef) {
          true
        } else {
          this.include(another.parent)
        }
    }
  }

  @tailrec
  private def getScopeRefPath(
      acc: Seq[Ast.Positioned],
      current: VariableScope
  ): Seq[Ast.Positioned] = {
    current match {
      case FunctionRoot      => acc
      case child: ChildScope => getScopeRefPath(acc :+ child.scopeRef, child.parent)
    }
  }

  def getScopeRefPath: Seq[Ast.Positioned] = getScopeRefPath(Seq.empty, this).reverse
}

trait VariableScoped {
  var variableScope: VariableScope = FunctionRoot

  def enterScope(scopeRef: Ast.Positioned): Unit = {
    val childScope = variableScope match {
      case FunctionRoot      => ChildScope(FunctionRoot, scopeRef, scopeRef.sourceIndex, 1)
      case scope: ChildScope => ChildScope(scope, scopeRef, scopeRef.sourceIndex, scope.depth + 1)
    }
    variableScope = childScope
  }

  def exitScope(scopeRef: Ast.Positioned): Unit = {
    val parentScope = variableScope match {
      case FunctionRoot => throw Compiler.Error("Invalid variable scope calcuation", None)
      case scope: ChildScope =>
        assume(scope.scopeRef eq scopeRef, "Invalid scope exit")
        scope.parent
    }
    variableScope = parentScope
  }

  def withScope[T](scopeRef: Ast.Positioned)(f: => T): T = {
    enterScope(scopeRef)
    val result = f
    exitScope(scopeRef)
    result
  }
}
