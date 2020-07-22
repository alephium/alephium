package org.alephium.util

import scala.collection.immutable.ArraySeq

class CollectionSpec extends AlephiumSpec {
  it should "get element safely" in {
    forAll { array: Array[Int] =>
      Collection.get(array, -1) is None
      Collection.get(array, array.length) is None
      if (array.nonEmpty) {
        Collection.get(array, 0) is Some(array(0))
        Collection.get(array, array.length - 1) is Some(array(array.length - 1))
      }

      val arraySeq = ArraySeq.from(array)
      Collection.get(arraySeq, -1) is None
      Collection.get(arraySeq, arraySeq.length) is None
      if (arraySeq.nonEmpty) {
        Collection.get(arraySeq, 0) is Some(arraySeq(0))
        Collection.get(arraySeq, arraySeq.length - 1) is Some(arraySeq(arraySeq.length - 1))
      }
    }
  }
}
