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

import org.alephium.protocol.Generators
import org.alephium.util.AlephiumSpec

class VersionSpec extends AlephiumSpec {
  it should "get version from release string" in {
    forAll(Generators.versionGen) { case (versionStr, version) =>
      Version.fromReleaseVersion(versionStr) contains version
    }
  }

  it should "compatible between same major version" in {
    val major    = 10
    val version1 = Version(major, 100, 0)
    val version2 = Version(major, 0, 1000)
    version1.compatible(version2) is true
  }

  it should "not compatible between different major version" in {
    val version1 = Version(10, 100, 0)
    val version2 = Version(9, 100, 0)
    version1.compatible(version2) is false
  }
}
