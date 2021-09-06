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

import org.scalatest.flatspec.AnyFlatSpecLike

// This is to verify that the `verify` and `fail` method in ModelSnapshots
// work as intended
class ModelSnapshotsSpec extends ModelSnapshots with AnyFlatSpecLike {
  it should "verify snapshots correctly" in {
    implicit val basePath = "src/test/resources/models/modelsnapshots"

    val validString = "valid string"
    validString.verify("valid-string")

    val invalidString = "invalid string"
    // valid encoding should be "0e696e76616c696420737472696e67"
    invalidString.fail("invalid-string")
  }
}
