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

package org.alephium.storage.setting

object StorageSetting {
  def syncWriteHDD(): StorageSetting =
    StorageSetting(disk = Disk.HDD, syncWrite = true)

  def apply(disk: Disk, syncWrite: Boolean): StorageSetting =
    new StorageSetting(disk, syncWrite)

}

/** Contains all settings needed by storage-engine for optimisations.
  */
class StorageSetting(val disk: Disk, val syncWrite: Boolean)
