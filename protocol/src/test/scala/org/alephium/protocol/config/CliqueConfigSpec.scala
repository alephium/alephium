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

package org.alephium.protocol.config

import org.alephium.util.AlephiumSpec

class CliqueConfigSpec extends AlephiumSpec {
  it should "validate broker id" in {
    val cliqueConfig = new CliqueConfig {
      override def brokerNum: Int = 3
      override def groups: Int    = 3
    }

    cliqueConfig.validate(0) is true
    cliqueConfig.validate(2) is true
    cliqueConfig.validate(-1) is false
    cliqueConfig.validate(3) is false
  }
}
