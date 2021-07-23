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
import org.scalatest.Assertion

import org.alephium.io.RocksDBSource
import org.alephium.protocol.config.ConsensusConfigFixture
import org.alephium.protocol.model.Version
import org.alephium.util.{AlephiumSpec, Files}

class NodeStateStorageSpec extends AlephiumSpec {
  trait Fixture extends ConsensusConfigFixture.Default {
    val tmpdir    = Files.tmpDir
    val dbname    = "node-state-storage-spec"
    val dbPath    = tmpdir.resolve(dbname)
    val dbVersion = Version(10, 20, 30)

    val source  = RocksDBSource.openUnsafe(dbPath, RocksDBSource.Compaction.HDD)
    val storage = NodeStateRockDBStorage(source, RocksDBSource.ColumnFamily.All)

    def setup(): Assertion = {
      storage.setDatabaseVersion(dbVersion).isRight is true
      storage.getDatabaseVersion isE Some(dbVersion)
    }

    def postTest(): Assertion = {
      source.dESTROY().isRight is true
    }

    val versionGen: Gen[Version] = for {
      major <- Gen.choose(0, Int.MaxValue)
      minor <- Gen.choose(0, Int.MaxValue)
      patch <- Gen.choose(0, Int.MaxValue)
    } yield Version(major, minor, patch)
  }

  it should "check database compatibility" in new Fixture {
    setup()
    forAll(versionGen) { version =>
      val dbVersion = storage.getDatabaseVersion.rightValue.get
      if (!version.backwardCompatible(dbVersion)) {
        storage.checkDatabaseCompatibility(version).isLeft is true
      } else if (dbVersion < version) {
        storage.checkDatabaseCompatibility(version).isRight is true
        storage.getDatabaseVersion isE Some(version)
      }
    }
    postTest()
  }

  it should "update database version when init" in new Fixture {
    storage.getDatabaseVersion isE None

    val version: Version = versionGen.sample.get
    storage.checkDatabaseCompatibility(version).isRight is true
    storage.getDatabaseVersion isE Some(version)
    postTest()
  }
}
