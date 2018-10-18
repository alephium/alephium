package org.alephium.util

import org.scalactic.Equality
import org.scalatest.prop.GeneratorDrivenPropertyChecks
import org.scalatest.words.ResultOfATypeInvocation
import org.scalatest.{Assertion, FlatSpecLike, Matchers}

trait AlephiumSpec extends FlatSpecLike with GeneratorDrivenPropertyChecks with Matchers {
  implicit class IsOps[A: Equality](left: A) {
    // scalastyle:off scalatest-matcher
    def is(right: A): Assertion                             = left shouldEqual right
    def is(right: ResultOfATypeInvocation[_]): Assertion    = left shouldBe right
    def isnot(right: A): Assertion                          = left should not equal right
    def isnot(right: ResultOfATypeInvocation[_]): Assertion = left should not be right
    // scalastyle:on scalatest-matcher
  }
}
