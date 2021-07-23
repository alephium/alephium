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

package org.alephium.protocol.model

import org.scalacheck.Gen

import org.alephium.util.AlephiumSpec

class VersionSpec extends AlephiumSpec {
  it should "get version from release string" in {
    val positiveInt = Gen.choose(0, Int.MaxValue)
    val versionStrGen: Gen[(String, Version)] = for {
      major    <- positiveInt
      minor    <- positiveInt
      patch    <- positiveInt
      commitId <- Gen.option(Gen.hexStr)
    } yield (
      s"$major.$minor.$patch${commitId.map(id => s"+$id").getOrElse("")}",
      Version(major, minor, patch)
    )

    forAll(versionStrGen) { case (versionStr, version) =>
      Version.fromReleaseVersion(versionStr) contains version
    }
  }
}
