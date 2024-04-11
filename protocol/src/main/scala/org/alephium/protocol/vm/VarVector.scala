// Copyright 2018 The Alephium Authors
// This file is part of the alephium project.
//
// The library is free software: you can redistribute it and/or modify
// it under the terms of the GNU Lesser General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// The library is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
// GNU Lesser General Public License for more details.
//
// You should have received a copy of the GNU Lesser General Public License
// along with the library. If not, see <http://www.gnu.org/licenses/>.

package org.alephium.protocol.vm

import java.math.BigInteger

import scala.collection.mutable

import org.alephium.util.AVector

final case class VarVector[T] private (
    underlying: mutable.ArraySeq[T],
    start: Int,
    length: Int
) {
  private def checkIndex(index: Int): Boolean = {
    index >= 0 && index < length
  }
  @inline
  private def validate[S](index: Int)(f: => S): ExeResult[S] = {
    if (checkIndex(index)) {
      Right(f)
    } else {
      failed(InvalidVarIndex(BigInteger.valueOf(index.toLong), length - 1))
    }
  }

  @inline def getUnsafe(index: Int): T = underlying(start + index)

  def get(index: Int): ExeResult[T] = {
    validate(index)(getUnsafe(index))
  }

  @inline def setUnsafe(index: Int, t: T): Unit = underlying(start + index) = t

  def set(index: Int, t: T): ExeResult[Unit] = {
    validate(index)(setUnsafe(index, t))
  }

  def setIf(index: Int, t: T, predicate: T => ExeResult[Unit]): ExeResult[Unit] = {
    if (!checkIndex(index)) {
      failed(InvalidVarIndex(BigInteger.valueOf(index.toLong), length - 1))
    } else {
      val oldT = underlying(start + index)
      predicate(oldT).map { _ =>
        setUnsafe(index, t)
      }
    }
  }

  def sameElements(ts: AVector[T]): Boolean = {
    length == ts.length && ts.indices.forall(idx => ts(idx) == getUnsafe(idx))
  }
}

object VarVector {
  val emptyVal: VarVector[Val] = unsafe(mutable.ArraySeq.empty[Val], 0, 0)

  def unsafe[T](underlying: mutable.ArraySeq[T], start: Int, length: Int): VarVector[T] =
    new VarVector(underlying, start, length)
}
