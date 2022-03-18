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

import org.alephium.protocol.model.{TxInput, TxOutputRef}
import org.alephium.protocol.vm.UnlockScript
import org.alephium.serde.{deserialize, serialize}

final case class AssetInput(outputRef: OutputRef, unlockScript: ByteString) {
  def toProtocol(): Either[String, TxInput] = {
    deserialize[UnlockScript](unlockScript)
      .map { unlock =>
        TxInput(
          outputRef.unsafeToAssetOutputRef(),
          unlock
        )
      }
      .left
      .map(_.getMessage)
  }
}

object AssetInput {
  def fromProtocol(txInput: TxInput): AssetInput = {
    AssetInput(OutputRef.from(txInput.outputRef), serialize(txInput.unlockScript))
  }

  def apply(outputRef: TxOutputRef, unlockScript: UnlockScript): AssetInput = {
    AssetInput(OutputRef.from(outputRef), serialize(unlockScript))
  }

  def from(input: TxInput): AssetInput = {
    apply(input.outputRef, input.unlockScript)
  }
}
