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

package org.alephium.flow.model

import org.alephium.util.AlephiumSpec

class AsyncUpdateStateSpec extends AlephiumSpec {
  it should "test AsyncUpdateState" in {
    val state = AsyncUpdateState()
    state.isUpdating is false
    state.requestCount is 0

    state.requestUpdate()
    state.requestCount is 1
    state.tryUpdate() is true
    state.isUpdating is true
    state.requestCount is 0

    state.tryUpdate() is false
    state.setCompleted()
    state.isUpdating is false
    state.tryUpdate() is false

    state.requestUpdate()
    state.requestCount is 1
    state.isUpdating is false
    state.requestUpdate()
    state.requestCount is 2
    state.isUpdating is false
    state.tryUpdate() is true
    state.isUpdating is true
    state.requestCount is 0
  }
}
