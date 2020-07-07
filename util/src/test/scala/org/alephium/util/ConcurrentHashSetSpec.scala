package org.alephium.util

class ConcurrentHashSetSpec extends AlephiumSpec {
  trait Fixture {
    val set = ConcurrentHashSet.empty[Int]
  }

  it should "add / remove /contains" in new Fixture {
    forAll { n: Int =>
      set.contains(n) is false
      set.add(n)
      set.contains(n) is true
      set.remove(n)
      set.contains(n) is false
      assertThrows[AssertionError](set.remove(n))
      set.removeIfExist(n)
    }
  }
}
