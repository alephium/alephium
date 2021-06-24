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

package org.alephium.util

object EitherF {
  // scalastyle:off return
  def foreachTry[E, L](elems: IterableOnce[E])(f: E => Either[L, Unit]): Either[L, Unit] = {
    elems.iterator.foreach { e =>
      f(e) match {
        case Left(l)  => return Left(l)
        case Right(_) => ()
      }
    }
    Right(())
  }

  def foldTry[E, L, R](elems: IterableOnce[E], zero: R)(
      op: (R, E) => Either[L, R]
  ): Either[L, R] = {
    var result = zero
    elems.iterator.foreach { e =>
      op(result, e) match {
        case Left(l)  => return Left(l)
        case Right(r) => result = r
      }
    }
    Right(result)
  }

  def forallTry[E, L](
      elems: IterableOnce[E]
  )(predicate: E => Either[L, Boolean]): Either[L, Boolean] = {
    elems.iterator.foreach { e =>
      predicate(e) match {
        case Right(true) => ()
        case result      => return result
      }
    }
    Right(true)
  }
  // scalastyle:on return
}
