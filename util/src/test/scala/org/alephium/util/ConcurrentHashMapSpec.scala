package org.alephium.util

class ConcurrentHashMapSpec extends AlephiumSpec {
  trait Fixture {
    val map = ConcurrentHashMap.empty[Int, Long]
  }

  it should "add / remove /contains" in new Fixture {
    forAll { (k: Int, v: Long) =>
      map.contains(k) is false
      map.add(k, v)
      map.contains(k) is true
      map.remove(k)
      map.contains(k) is false
      map.remove(k)
    }
  }
}
