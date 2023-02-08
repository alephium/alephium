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

import org.alephium.api.{badRequest, Try}
import org.alephium.protocol.{vm, PublicKey}
import org.alephium.protocol.model.BlockHash
import org.alephium.protocol.vm.{GasBox, GasPrice, StatefulContract}
import org.alephium.serde._
import org.alephium.util.AVector

@SuppressWarnings(Array("org.wartremover.warts.DefaultArguments"))
final case class BuildDeployContractTx(
    fromPublicKey: PublicKey,
    bytecode: ByteString,
    initialAttoAlphAmount: Option[Amount] = None,
    initialTokenAmounts: Option[AVector[Token]] = None,
    issueTokenAmount: Option[Amount] = None,
    gasAmount: Option[GasBox] = None,
    gasPrice: Option[GasPrice] = None,
    targetBlockHash: Option[BlockHash] = None
) extends BuildTxCommon {
  def decodeBytecode(): Try[BuildDeployContractTx.Code] = {
    deserialize[BuildDeployContractTx.Code](bytecode).left.map(serdeError =>
      badRequest(serdeError.getMessage)
    )
  }
}

object BuildDeployContractTx {
  final case class Code(
      contract: StatefulContract,
      initialImmFields: AVector[vm.Val],
      initialMutFields: AVector[vm.Val]
  )
  object Code {
    implicit val serde: Serde[Code] = {
      val _serde: Serde[Code] =
        Serde.forProduct3(Code.apply, t => (t.contract, t.initialImmFields, t.initialMutFields))

      _serde.validate(code =>
        if (code.contract.validate(code.initialImmFields, code.initialMutFields)) {
          Right(())
        } else {
          Left(
            s"Invalid field length, expect ${code.contract.fieldLength}, " +
              s"have ${code.initialImmFields.length} immutable fields and " +
              s"${code.initialMutFields.length} mutable fields"
          )
        }
      )
    }
  }
}
