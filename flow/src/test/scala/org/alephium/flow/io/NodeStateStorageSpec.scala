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

import org.scalacheck.Gen

import org.alephium.io.RocksDBSource
import org.alephium.protocol.model.Version
import org.alephium.util.AlephiumSpec

class NodeStateStorageSpec extends AlephiumSpec with StorageSpec[NodeStateRockDBStorage] {

  override val dbname: String = "node-state-storage-spec"
  override val builder: RocksDBSource => NodeStateRockDBStorage =
    source => NodeStateRockDBStorage(source, RocksDBSource.ColumnFamily.All)

  val versionGen: Gen[Version] = for {
    major <- Gen.choose(0, Int.MaxValue)
    minor <- Gen.choose(0, Int.MaxValue)
    patch <- Gen.choose(0, Int.MaxValue)
  } yield Version(major, minor, patch)

  it should "check database compatibility" in {
    val initNodeVersion = versionGen.sample.get
    storage.setNodeVersion(initNodeVersion).isRight is true
    storage.getNodeVersion isE Some(initNodeVersion)

    forAll(versionGen) { version =>
      val nodeVersion = storage.getNodeVersion.rightValue.get
      if (!version.backwardCompatible(nodeVersion)) {
        storage.checkNodeCompatibility(version).isLeft is true
      } else if (nodeVersion < version) {
        storage.checkNodeCompatibility(version).isRight is true
        storage.getNodeVersion isE Some(version)
      }
    }
  }

  it should "update database version when init" in {
    storage.getNodeVersion isE None

    val version: Version = versionGen.sample.get
    storage.checkNodeCompatibility(version).isRight is true
    storage.getNodeVersion isE Some(version)
  }
}
