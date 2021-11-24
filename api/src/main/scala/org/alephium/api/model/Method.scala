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

package org.alephium.api.model

import akka.util.ByteString

import org.alephium.protocol.vm
import org.alephium.serde._
import org.alephium.util.AVector

final case class Method(
    isPublic: Boolean,
    isPayable: Boolean,
    argsLength: Int,
    localsLength: Int,
    returnLength: Int,
    instrs: AVector[ByteString] //TODO Replace by a human readable format?
) {
  def toProtocol(): Either[String, vm.Method[vm.StatefulContext]] = {
    Method.instrsToProtocol(instrs).map { protocolInstrs =>
      vm.Method(
        isPublic,
        isPayable,
        argsLength,
        localsLength,
        returnLength,
        protocolInstrs
      )
    }
  }
}

object Method {
  def fromProtocol(method: vm.Method[vm.StatefulContext]): Method = {
    Method(
      method.isPublic,
      method.isPayable,
      method.argsLength,
      method.localsLength,
      method.returnLength,
      method.instrs.map(serialize(_))
    )
  }

  def instrsToProtocol(
      instrs: AVector[ByteString]
  ): Either[String, AVector[vm.Instr[vm.StatefulContext]]] = {
    instrs.mapE { bytes =>
      for {
        instrs <- deserialize[vm.Instr[vm.StatefulContext]](bytes).left.map(_.getMessage)
      } yield instrs
    }
  }
}
