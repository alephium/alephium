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

final private[flow] class AsyncUpdateState(
    private var _requestCount: Int,
    private var _isUpdating: Boolean
) {
  @inline def requestCount: Int   = _requestCount
  @inline def isUpdating: Boolean = _isUpdating

  @inline def tryUpdate(): Boolean = {
    val needToUpdate = _requestCount > 0 && !_isUpdating
    if (needToUpdate) {
      _requestCount = 0
      _isUpdating = true
    }
    needToUpdate
  }

  @inline def requestUpdate(): Unit = _requestCount += 1
  @inline def setCompleted(): Unit  = _isUpdating = false
}

private[flow] object AsyncUpdateState {
  def apply(): AsyncUpdateState = new AsyncUpdateState(0, false)
}
