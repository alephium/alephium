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

package org.alephium.protocol.vm.lang

import org.alephium.protocol.vm.lang.Ast.EventDef
import org.alephium.util.AlephiumSpec

class AstSpec extends AlephiumSpec {
  it should "encode event index" in {
    {
      val code         = EventDef.eventCode(3, EventDef.contractEventType)
      val (index, tpe) = code.indexAndType
      index is 3.toByte
      tpe is EventDef.contractEventType
    }

    {
      val code         = EventDef.eventCode(3, EventDef.contractEventWithTxIdIndexType)
      val (index, tpe) = code.indexAndType
      index is 3.toByte
      tpe is EventDef.contractEventWithTxIdIndexType
    }

    {
      val code         = EventDef.EventCode(-11)
      val (index, tpe) = code.indexAndType
      index is -11.toByte
      tpe is -11
    }
  }
}
