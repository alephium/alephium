package org.alephium.macros

import scala.language.experimental.macros
import scala.reflect.macros.whitebox._

/*
 * Extracted, with some modifications, from spire
 * https://github.com/non/spire.git
 */
object HPC {

  def cfor[A](init: A)(test: A => Boolean, next: A => A)(body: A => Unit): Unit =
    macro cforMacro[A]

  def cforMacro[A](c: Context)(init: c.Expr[A])(test: c.Expr[A => Boolean], next: c.Expr[A => A])(
      body: c.Expr[A                                           => Unit]): c.Expr[Unit] = {

    import c.universe._
    val util  = SyntaxUtil[c.type](c)
    val index = util.name("index")

    @SuppressWarnings(Array("org.wartremover.warts.Nothing"))
    val tree = if (util.isClean(test, next, body)) {
      q"""
      var $index = $init
      while ($test($index)) {
        $body($index)
        $index = $next($index)
      }
      """
    } else {
      val testName = util.name("test")
      val nextName = util.name("next")
      val bodyName = util.name("body")

      q"""
      val $testName: Int => Boolean = $test
      val $nextName: Int => Int = $next
      val $bodyName: Int => Unit = $body
      var $index: Int = $init
      while ($testName($index)) {
        $bodyName($index)
        $index = $nextName($index)
      }
      """
    }

    new InlineUtil[c.type](c).inlineAndReset[Unit](tree)
  }
}

// scalastyle:off
final case class SyntaxUtil[C <: Context with Singleton](val c: C) {

  import c.universe._

  def name(s: String): TermName = Compat.freshTermName(c)(s + "$")

  def names(bs: String*): List[TermName] = bs.toList.map(name)

  def isClean(es: c.Expr[_]*): Boolean =
    es.forall {
      _.tree match {
        case t @ Ident(_: TermName) if t.symbol.asTerm.isStable => true
        case Function(_, _)                                     => true
        case _                                                  => false
      }
    }
}

class InlineUtil[C <: Context with Singleton](val c: C) {
  import c.universe._

  @SuppressWarnings(Array("org.wartremover.warts.NonUnitStatements"))
  def inlineAndReset[T](tree: Tree): c.Expr[T] = {
    val inlined = inlineApplyRecursive(tree)
    c.Expr[T](Compat.resetLocalAttrs(c)(inlined))
  }

  def inlineApplyRecursive(tree: Tree): Tree = {
    val ApplyName = Compat.termName(c)("apply")

    class InlineSymbol(name: TermName, symbol: Symbol, value: Tree) extends Transformer {
      @SuppressWarnings(Array("org.wartremover.warts.Recursion"))
      override def transform(tree: Tree): Tree = tree match {
        case tree: Ident if tree.symbol == symbol =>
          if (tree.name == name) {
            value
          } else {
            super.transform(tree)
          }

        case tt: TypeTree if tt.original != null =>
          super.transform(Compat.setOrig(c)(TypeTree(), transform(tt.original)))
        case _ =>
          super.transform(tree)
      }
    }

    object InlineApply extends Transformer {
      def inlineSymbol(name: TermName, symbol: Symbol, body: Tree, arg: Tree): Tree =
        new InlineSymbol(name, symbol, arg).transform(body)

      override def transform(tree: Tree): Tree = tree match {
        case Apply(Select(Function(params, body), ApplyName), args) =>
          params.zip(args).foldLeft(body) {
            case (b, (param, arg)) =>
              inlineSymbol(param.name, param.symbol, b, arg)
          }

        case Apply(Function(params, body), args) =>
          params.zip(args).foldLeft(body) {
            case (b, (param, arg)) =>
              inlineSymbol(param.name, param.symbol, b, arg)
          }

        case _ =>
          super.transform(tree)
      }
    }

    InlineApply.transform(tree)
  }
}

object Compat {
  type Context = scala.reflect.macros.whitebox.Context

  def freshTermName[C <: Context](c: C)(s: String): c.universe.TermName =
    c.universe.TermName(c.freshName(s))

  def termName[C <: Context](c: C)(s: String): c.universe.TermName =
    c.universe.TermName(s)

  def typeCheck[C <: Context](c: C)(t: c.Tree): c.Tree =
    c.typecheck(t)

  def resetLocalAttrs[C <: Context](c: C)(t: c.Tree): c.Tree =
    c.untypecheck(t)

  def setOrig[C <: Context](c: C)(tt: c.universe.TypeTree, t: c.Tree): c.universe.TypeTree =
    c.universe.internal.setOriginal(tt, t)

  def predef[C <: Context](c: C): c.Tree = {
    import c.universe._
    q"scala.Predef"
  }
}
