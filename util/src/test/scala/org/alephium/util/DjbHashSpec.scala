package org.alephium.util

import akka.util.ByteString

class DjbHashSpec extends AlephiumSpec {
  it should "hash correctly" in {
    def check(string: String, expected: Int) = {
      val bytes = ByteString.fromString(string)
      DjbHash.intHash(bytes) is expected
    }

    check("", 5381)
    check("a", 177670)
    check("z", 177695)
    check("foo", 193491849)
    check("bar", 193487034)
  }
}
