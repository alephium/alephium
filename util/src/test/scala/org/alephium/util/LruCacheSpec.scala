package org.alephium.util

import org.scalatest.Assertion

class LruCacheSpec extends AlephiumSpec {
  trait Fixture {
    val cache = LruCache[Char, Int, Unit](maxCapacity = 2)

    def testKeys(keys: Char*): Assertion = {
      cache.keys.toSet is Set(keys: _*)
    }
  }

  it should "test get" in new Fixture {
    def testGet(key: Char, value: Int, expected: Int): Assertion = {
      cache.get(key)(Right(value)) isE expected
      cache.get(key)(???) isE expected
      cache.exists(key)(???) isE true
      cache.exists('z')(Right(true)) isE true
      cache.exists('z')(Right(false)) isE false
    }

    testGet('a', 0, 0)
    testKeys('a')
    testGet('b', 1, 1)
    testKeys('a', 'b')
    testGet('a', 1, 0)
    testKeys('a', 'b')
    testGet('c', 2, 2)
    testKeys('a', 'c')
  }

  it should "test getUnsafe" in new Fixture {
    def testGetUnsafe(key: Char, value: Int, expected: Int): Assertion = {
      cache.getUnsafe(key)(value) is expected
      cache.getUnsafe(key)(???) is expected
      cache.existsUnsafe(key)(???) is true
      cache.existsUnsafe('z')(true) is true
      cache.existsUnsafe('z')(false) is false
    }

    testGetUnsafe('a', 0, 0)
    testKeys('a')
    testGetUnsafe('b', 1, 1)
    testKeys('a', 'b')
    testGetUnsafe('a', 1, 0)
    testKeys('a', 'b')
    testGetUnsafe('c', 2, 2)
    testKeys('a', 'c')
  }

  it should "test getOpt" in new Fixture {
    def testGetOpt(key: Char, value: Int, expected: Int): Assertion = {
      cache.getOpt(key)(Right(Option(value))) isE Option(expected)
      cache.getOpt(key)(???) isE Option(expected)
      cache.exists(key)(???) isE true
      cache.exists('z')(Right(true)) isE true
      cache.exists('z')(Right(false)) isE false
    }

    testGetOpt('a', 0, 0)
    testKeys('a')
    testGetOpt('b', 1, 1)
    testKeys('a', 'b')
    testGetOpt('a', 1, 0)
    testKeys('a', 'b')
    testGetOpt('c', 2, 2)
    testKeys('a', 'c')
  }

  it should "test getOptUnsafe" in new Fixture {
    def testGetOptUnsafe(key: Char, value: Int, expected: Int): Assertion = {
      cache.getOptUnsafe(key)(Option(value)) is Option(expected)
      cache.getOptUnsafe(key)(???) is Option(expected)
      cache.existsUnsafe(key)(???) is true
      cache.existsUnsafe('z')(true) is true
      cache.existsUnsafe('z')(false) is false
    }

    testGetOptUnsafe('a', 0, 0)
    testKeys('a')
    testGetOptUnsafe('b', 1, 1)
    testKeys('a', 'b')
    testGetOptUnsafe('a', 1, 0)
    testKeys('a', 'b')
    testGetOptUnsafe('c', 2, 2)
    testKeys('a', 'c')
  }
}
