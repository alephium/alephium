package org.alephium.macros

import scala.collection.immutable.TreeSet

import org.scalatest.flatspec.AnyFlatSpecLike

class EnumerationMacrosSpec extends AnyFlatSpecLike {
  import EnumerationMacrosSpec._
  implicit val ordering: Ordering[Foo] = Ordering.by(_.getClass.getSimpleName)

  it should "enumarate all the sub-classes" in {
    val enumerated = EnumerationMacros.sealedInstancesOf[Foo]
    val expected   = TreeSet[Foo](Bar, Baz)
    assert(enumerated equals expected)
  }
}

object EnumerationMacrosSpec {
  sealed trait Foo
  case object Bar extends Foo
  case object Baz extends Foo
}
