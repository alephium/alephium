package org.alephium.util

import scala.collection.immutable.ArraySeq

object Collection {
  def get[T](array: Array[T], index: Int): Option[T] = {
    Option.when(checkIndex(array, index))(array(index))
  }

  @inline def checkIndex[T](array: Array[T], index: Int): Boolean = {
    index >= 0 && index < array.length
  }

  def get[T](array: ArraySeq[T], index: Int): Option[T] = {
    Option.when(checkIndex(array, index))(array(index))
  }

  @inline def checkIndex[T](array: ArraySeq[T], index: Int): Boolean = {
    index >= 0 && index < array.length
  }
}
