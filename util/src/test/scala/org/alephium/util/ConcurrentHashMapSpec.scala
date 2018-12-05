package org.alephium.util

class ConcurrentHashMapSpec extends AlephiumSpec {
  trait Fixture {
    val map = ConcurrentHashMap.empty[Int, Long]
  }

  it should "put / remove /contains" in new Fixture {
    forAll { (k: Int, v: Long) =>
      map.contains(k) is false
      map.put(k, v)
      map.contains(k) is true
      assertThrows[AssertionError](map.put(k, v))
      map.remove(k)
      map.contains(k) is false
      assertThrows[AssertionError](map.remove(k))
      map.removeIfExist(k)
    }
  }

  it should "foreach / reduce" in new Fixture {
    map.put(0, 0)
    map.put(1, 2)
    map.put(2, 4)

    var sum = 0l
    map.foreachValue(sum += _)
    sum is 6

    map.reduceValuesBy(identity)(_ + _) is 6
    map.reduceValuesBy(identity)(math.max) is 4
    map.reduceValuesBy(identity)(math.min) is 0
    map.reduceValuesBy(-_)(math.max) is 0
    map.reduceValuesBy(-_)(math.min) is -4
  }
}
