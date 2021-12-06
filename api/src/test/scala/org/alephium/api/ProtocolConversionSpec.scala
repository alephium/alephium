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

package org.alephium.api

import org.scalatest.{Assertion, EitherValues}

import org.alephium.api.model._
import org.alephium.json.Json._
import org.alephium.protocol.{model => protocol}
import org.alephium.protocol.vm
import org.alephium.util._

class ProtocolConversionSpec extends AlephiumSpec with EitherValues with NumericHelpers {
  it should "convert Method" in new Fixture {
    checkData[Method, vm.Method[vm.StatefulContext]](
      method,
      Method.fromProtocol,
      _.toProtocol().rightValue
    )
  }

  it should "convert Script" in new Fixture {
    checkData[Script, vm.StatefulScript](script, Script.fromProtocol, _.toProtocol().rightValue)
  }

  it should "convert TxOutputRef" in new Fixture {
    checkData[OutputRef, protocol.TxOutputRef](
      assetTxOutputRef,
      OutputRef.from,
      _.unsafeToAssetOutputRef()
    )
  }

  it should "convert TxInput" in new Fixture {
    checkData[Input.Asset, protocol.TxInput](
      txInput,
      Input.Asset.fromProtocol,
      _.toProtocol().rightValue
    )
  }

  it should "convert AssetOutput" in new Fixture {
    checkData[Output.Asset, protocol.AssetOutput](
      assetOutput,
      Output.Asset.fromProtocol,
      _.toProtocol()
    )
  }

  it should "convert UnsignedTransaction" in new Fixture {
    checkData[UnsignedTx, protocol.UnsignedTransaction](
      unsignedTransaction,
      UnsignedTx.fromProtocol,
      _.toProtocol().rightValue
    )

    UnsignedTx
      .fromProtocol(unsignedTransaction)
      .copy(hash = Some(hashGen.sample.get))
      .toProtocol()
      .leftValue is "Invalid hash"
  }

  it should "convert Transaction" in new Fixture {
    checkData[Transaction, protocol.Transaction](
      transaction,
      Transaction.fromProtocol,
      _.toProtocol().rightValue
    )
  }

  it should "convert TransactionTemplate" in new Fixture {
    checkData[TransactionTemplate, protocol.TransactionTemplate](
      transactionTemplate,
      TransactionTemplate.fromProtocol,
      _.toProtocol().rightValue
    )
  }

  trait Fixture extends ApiModelFixture {
    def checkData[T: Reader: Writer, P](
        protocol: P,
        convertToApi: P => T,
        convertToProtocol: T => P
    ): Assertion = {
      convertToProtocol(read[T](write(convertToApi(protocol)))) is protocol
    }
  }
}
