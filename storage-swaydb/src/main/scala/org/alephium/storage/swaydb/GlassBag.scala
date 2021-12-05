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

package org.alephium.storage.swaydb

import swaydb.{Bag, Glass, IO}

import org.alephium.io.IOException.StorageException

object GlassBag {

  /** Converts all exceptions from SwayDB to [[StorageException]]
    */
  @inline def convertException[T](f: => T): T =
    try f
    catch {
      case throwable: Throwable =>
        throw StorageException(throwable)
    }

  /** This bag a copy of [[swaydb.Bag.glass]] which simply wraps
    * all [[Throwable]]s to [[StorageException]].
    */
  val bag: Bag.Sync[Glass] =
    new Bag.Sync[Glass] {
      override def isSuccess[A](a: Glass[A]): Boolean = true

      override def isFailure[A](a: Glass[A]): Boolean = false

      override def exception[A](a: Glass[A]): Option[Throwable] = None

      override def getOrElse[A, B >: A](a: Glass[A])(b: => B): B = a

      override def getUnsafe[A](a: Glass[A]): A = a

      override def orElse[A, B >: A](a: Glass[A])(b: Glass[B]): B = a

      override def unit: Unit = ()

      override def none[A]: Option[A] = Option.empty[A]

      override def apply[A](a: => A): A = convertException(a)

      override def foreach[A](a: Glass[A])(f: A => Unit): Unit = convertException(f(a))

      override def map[A, B](a: Glass[A])(f: A => B): B = convertException(f(a))

      override def transform[A, B](a: Glass[A])(f: A => B): B = convertException(f(a))

      override def flatMap[A, B](fa: Glass[A])(f: A => Glass[B]): B = convertException(f(fa))

      override def success[A](value: A): A = value

      override def failure[A](exception: Throwable): A = convertException(throw exception)

      override def fromIO[E: IO.ExceptionHandler, A](a: IO[E, A]): A = a.get

      override def suspend[B](f: => Glass[B]): B = convertException(f)

      override def safe[B](f: => Glass[B]): B = convertException(f)

      override def flatten[A](fa: Glass[A]): A = fa

      override def recover[A, B >: A](fa: Glass[A])(pf: PartialFunction[Throwable, B]): B = fa

      override def recoverWith[A, B >: A](fa: Glass[A])(
          pf: PartialFunction[Throwable, Glass[B]]
      ): B = fa
    }

}
