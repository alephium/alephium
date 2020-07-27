package org.alephium.util

import scala.collection.immutable.ArraySeq

object Collection {
  def get[T](array: Array[T], index: Int): Option[T] = {
    Option.when(array.isDefinedAt(index))(array(index))
  }

  def get[T](array: ArraySeq[T], index: Int): Option[T] = {
    Option.when(array.isDefinedAt(index))(array(index))
  }
}
