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

import org.scalatest.EitherValues

import org.alephium.api.model._
import org.alephium.util._

class ProtocolConversionSpec extends AlephiumSpec with EitherValues with NumericHelpers {
  it should "convert Method" in new Fixture {
    Method.fromProtocol(method).toProtocol() isE method
  }

  it should "convert Script" in new Fixture {
    Script.fromProtocol(script).toProtocol() isE script
  }

  it should "convert TxOutputRef" in new Fixture {
    OutputRef.from(assetTxOutputRef).unsafeToAssetOutputRef() is assetTxOutputRef
  }

  it should "convert TxInput" in new Fixture {
    Input.Asset.fromProtocol(txInput).toProtocol() isE txInput
  }

  it should "convert AssetOutput" in new Fixture {
    Output.Asset.fromProtocol(assetOutput).toProtocol() is assetOutput
  }

  it should "convert UnsignedTransaction" in new Fixture {
    UnsignedTx.fromProtocol(unsignedTransaction).toProtocol() isE unsignedTransaction
    UnsignedTx
      .fromProtocol(unsignedTransaction)
      .copy(hash = Some(hashGen.sample.get))
      .toProtocol()
      .leftValue is "Invalid hash"
  }

  it should "convert Transaction" in new Fixture {
    Transaction.fromProtocol(transaction).toProtocol() isE transaction
  }

  trait Fixture extends ApiModelFixture
}
