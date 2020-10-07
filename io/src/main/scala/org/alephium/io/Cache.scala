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

package org.alephium.io

sealed trait Cache[V]

sealed trait ValueExisted[V] {
  def value: V
}

sealed trait KeyExistedInDB

final case class Cached[V](value: V) extends Cache[V] with KeyExistedInDB with ValueExisted[V]

sealed trait Modified[V]               extends Cache[V]
final case class Inserted[V](value: V) extends Modified[V] with ValueExisted[V]
final case class Removed[V]()          extends Modified[V] with KeyExistedInDB
final case class Updated[V](value: V)  extends Modified[V] with KeyExistedInDB with ValueExisted[V]
