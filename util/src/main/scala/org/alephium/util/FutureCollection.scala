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

import scala.concurrent.{ExecutionContext, Future}

@SuppressWarnings(Array("org.wartremover.warts.Recursion"))
object FutureCollection {

  /** Sequentially executes a async function for each element in a collection. Will not start the
    * processing of the next element until the previous one finishes.
    *
    * @return
    *   A future with the accumulated result of all processed elements.
    */
  def foldSequentialE[I, L, R](
      xs: AVector[I]
  )(
      init: R
  )(f: (R, I) => Future[Either[L, R]])(implicit ec: ExecutionContext): Future[Either[L, R]] = {
    def next(out: R, xs: AVector[I]): Future[Either[L, R]] = {
      xs.headOption match {
        case None =>
          Future.successful(Right(out))
        case Some(x) =>
          f(out, x).flatMap { newOut =>
            newOut match {
              case Left(_)  => Future.successful(newOut)
              case Right(r) => next(r, xs.drop(1))
            }
          }
      }
    }

    next(init, xs)
  }

}
