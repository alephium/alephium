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

package org.alephium.storage.util

import org.alephium.io.{IOError, IOResult, IOUtils}
import org.alephium.io.IOException.StorageException
import org.alephium.storage.KeyValueSource

object StorageIOUtil {

  /** Used by storage engines during initialisation.
    *
    * TODO - Should this be in [[IOUtils]]. But io does
    *        not have nay dependency on storage project.
    */
  @inline
  def tryOpen[T <: KeyValueSource](f: => T): IOResult[T] = {
    try Right(f)
    catch {
      case throwable: Throwable =>
        val pf = IOUtils.error
        if (pf.isDefinedAt(throwable)) {
          pf(throwable)
        } else {
          Left(
            IOError.Storage(
              new StorageException(throwable)
            )
          )
        }
    }
  }

}
