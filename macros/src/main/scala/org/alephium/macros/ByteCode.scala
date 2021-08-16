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

import scala.annotation.{compileTimeOnly, nowarn, StaticAnnotation}
import scala.language.experimental.macros
import scala.reflect.macros.whitebox

@compileTimeOnly("enable macro paradise to expand macro annotations")
class ByteCode extends StaticAnnotation {
  def macroTransform(annottees: Any*): Any = macro ByteCodeImpl.impl
}

object ByteCodeImpl {
  def impl(c: whitebox.Context)(annottees: c.Expr[Any]*): c.Expr[Any] = {
    import c.universe._

    def abort() = c.abort(c.enclosingPosition, s"Invalid annottee")

    @nowarn def addByteCode(classDef: c.universe.ClassDef, compDef: ModuleDef): c.Expr[Any] =
      (classDef, compDef) match {
        case (
              q"final case class $className(..$fields) extends ..$parents { ..$body }",
              q"object $comp extends ..$compBase { ..$compBody }"
            ) =>
          c.Expr[Any](
            q"""final case class $className ( ..$fields ) extends ..$parents {
              def code: Byte = $comp.code
              ..$body
            }
            object $comp extends ..$compBase { ..$compBody }
            """
          )
        case _ => abort()
      }

    annottees.map(_.tree) match {
      case (classDef: ClassDef) :: (compDef: ModuleDef) :: Nil => addByteCode(classDef, compDef)
      case _                                                   => abort()
    }
  }
}
