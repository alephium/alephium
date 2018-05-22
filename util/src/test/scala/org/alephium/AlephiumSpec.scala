package org.alephium

import org.scalactic.Equality
import org.scalatest.{Assertion, FlatSpecLike, Matchers}
import org.scalatest.prop.GeneratorDrivenPropertyChecks

trait AlephiumSpec extends FlatSpecLike with GeneratorDrivenPropertyChecks with Matchers {
  implicit class ShouldEqOps[A: Equality](left: A) {
    def shouldEq(right: A): Assertion = left shouldEqual right
  }
}
