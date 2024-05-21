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

import org.scalatest.BeforeAndAfterEach

import org.alephium.io.RocksDBSource
import org.alephium.util.{AlephiumSpec, Files}

trait StorageSpec[S] extends AlephiumSpec with BeforeAndAfterEach {
  val dbname: String
  val builder: RocksDBSource => S
  lazy val dbPath           = Files.tmpDir.resolve(dbname)
  var source: RocksDBSource = _
  var storage: S            = _

  override def beforeEach(): Unit = {
    super.beforeEach()
    source = RocksDBSource.openUnsafe(dbPath)
    storage = builder(source)
  }

  override def afterEach(): Unit = {
    super.afterEach()
    source.dESTROY().rightValue
    ()
  }
}
