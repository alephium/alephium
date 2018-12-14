package org.alephium.util

import scala.language.experimental.macros

import scala.reflect.macros.blackbox.Context
import scala.collection.immutable.TreeSet

object EnumerationMacros {
  def sealedInstancesOf[A]: TreeSet[A] = macro sealedInstancesOf_impl[A]

  def sealedInstancesOf_impl[A: c.WeakTypeTag](
      c: Context): c.Expr[scala.collection.immutable.TreeSet[A]] = {
    import c.universe._

    val symbol = weakTypeOf[A].typeSymbol.asClass

    if (!symbol.isClass || !symbol.isSealed)
      c.abort(c.enclosingPosition, "Can only enumerate values of a sealed trait or class.")
    else {

      val children = symbol.knownDirectSubclasses.toList

      if (!children.forall(_.isModuleClass))
        c.abort(c.enclosingPosition, "All children must be objects.")
      else
        c.Expr[TreeSet[A]] {

          def sourceModuleRef(sym: Symbol) =
            Ident(
              sym
                .asInstanceOf[scala.reflect.internal.Symbols#Symbol]
                .sourceModule
                .asInstanceOf[Symbol])

          Apply(
            Select(
              reify(TreeSet).tree,
              TermName("apply")
            ),
            children.map(sourceModuleRef(_))
          )
        }
    }
  }
}
