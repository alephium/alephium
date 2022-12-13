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
import org.alephium.protocol.model.{BlockHash, TokenId}
import org.alephium.protocol.vm.{GasBox, GasPrice, StatefulContract}
import org.alephium.serde._
import org.alephium.util.{AVector, U256}

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
  def getInitialAttoAlphAmount: Either[String, Option[U256]] = {
    val alphTokenOpt = initialTokenAmounts.flatMap(_.find(_.id == TokenId.alph))
    (initialAttoAlphAmount, alphTokenOpt) match {
      case (Some(attoAlphAmount), Some(alphToken)) =>
        attoAlphAmount.value.add(alphToken.amount) match {
          case Some(amount) => Right(Some(amount))
          case None         => Left("ALPH amount overflow")
        }
      case (Some(attoAlphAmount), None) => Right(Some(attoAlphAmount.value))
      case (None, Some(alphToken))      => Right(Some(alphToken.amount))
      case (None, None)                 => Right(None)
    }
  }

  def getInitialTokenAmounts: AVector[Token] = {
    initialTokenAmounts.map(_.filterNot(_.id == TokenId.alph)).getOrElse(AVector.empty[Token])
  }
}

object BuildDeployContractTx {
  final case class Code(contract: StatefulContract, initialFields: AVector[vm.Val])
  object Code {
    implicit val serde: Serde[Code] = {
      val _serde: Serde[Code] = Serde.forProduct2(Code.apply, t => (t.contract, t.initialFields))

      _serde.validate(code =>
        if (code.contract.validate(code.initialFields)) {
          Right(())
        } else {
          Left(
            s"Invalid field length, expect ${code.contract.fieldLength}, have ${code.initialFields.length}"
          )
        }
      )
    }
  }

  def decode(bytecode: ByteString): Try[Code] = {
    deserialize[Code](bytecode).left.map(serdeError => badRequest(serdeError.getMessage))
  }
}
