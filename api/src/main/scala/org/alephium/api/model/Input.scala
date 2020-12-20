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

import org.alephium.protocol.model.{ContractOutputRef, TxInput, TxOutputRef}
import org.alephium.protocol.vm.UnlockScript
import org.alephium.serde.serialize

final case class Input(outputRef: OutputRef, unlockScript: Option[ByteString])

object Input {
  def apply(outputRef: TxOutputRef, unlockScript: UnlockScript): Input = {
    Input(OutputRef.from(outputRef), Some(serialize(unlockScript)))
  }

  def from(input: TxInput): Input = {
    Input(input.outputRef, input.unlockScript)
  }

  def from(input: ContractOutputRef): Input =
    Input(OutputRef.from(input), unlockScript = None)
}
