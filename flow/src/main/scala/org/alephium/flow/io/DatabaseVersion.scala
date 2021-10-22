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

package org.alephium.flow.io

import akka.util.ByteString

import org.alephium.serde.Serde
import org.alephium.util.Bytes

final case class DatabaseVersion(value: Int) extends AnyVal

object DatabaseVersion {
  implicit val serde: Serde[DatabaseVersion] = Serde.forProduct1(apply, _.value)

  val currentDBVersion: DatabaseVersion = DatabaseVersion(Bytes.toIntUnsafe(ByteString(0, 0, 0, 0)))
}
