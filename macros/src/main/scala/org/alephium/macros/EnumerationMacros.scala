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

package org.alephium.macros

import scala.collection.immutable.TreeSet
import scala.language.experimental.macros
import scala.reflect.macros.blackbox.Context

object EnumerationMacros {
  def sealedInstancesOf[A]: TreeSet[A] = macro sealedInstancesOf_impl[A]

  def sealedInstancesOf_impl[A: c.WeakTypeTag](
      c: Context
  ): c.Expr[scala.collection.immutable.TreeSet[A]] = {
    import c.universe._

    val symbol = weakTypeOf[A].typeSymbol.asClass

    def sourceModuleRef(sym: Symbol): Ident = {
      Ident(
        sym
          .asInstanceOf[scala.reflect.internal.Symbols#Symbol]
          .sourceModule
          .asInstanceOf[Symbol]
      )
    }

    if (!symbol.isClass || !symbol.isSealed) {
      c.abort(c.enclosingPosition, "Can only enumerate values of a sealed trait or class.")
    } else {
      val children = symbol.knownDirectSubclasses.toList
      if (!children.forall(_.isModuleClass)) {
        c.abort(c.enclosingPosition, "All children must be objects.")
      } else {
        c.Expr[TreeSet[A]] {
          Apply(Select(reify(TreeSet).tree, TermName("apply")), children.map(sourceModuleRef(_)))
        }
      }
    }
  }
}
