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

import org.alephium.protocol.{model => protocol}
import org.alephium.protocol.Signature
import org.alephium.protocol.config.NetworkConfig
import org.alephium.serde._
import org.alephium.util.AVector

final case class TransactionTemplate(
    unsigned: UnsignedTx,
    inputSignatures: AVector[ByteString],
    scriptSignatures: AVector[ByteString]
) {
  def toProtocol()(implicit
      networkConfig: NetworkConfig
  ): Either[String, protocol.TransactionTemplate] = {
    for {
      unsignedTx <- unsigned.toProtocol()
      inputSig   <- inputSignatures.mapE(deserialize[Signature]).left.map(_.getMessage())
      scriptSig  <- scriptSignatures.mapE(deserialize[Signature]).left.map(_.getMessage())
    } yield {
      protocol.TransactionTemplate(
        unsignedTx,
        inputSig,
        scriptSig
      )
    }
  }
}

object TransactionTemplate {
  def fromProtocol(template: protocol.TransactionTemplate): TransactionTemplate = {
    TransactionTemplate(
      UnsignedTx.fromProtocol(template.unsigned),
      template.inputSignatures.map(sig => serialize(sig)),
      template.scriptSignatures.map(sig => serialize(sig))
    )
  }
}
