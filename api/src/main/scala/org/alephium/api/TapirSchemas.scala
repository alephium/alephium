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

import java.math.BigInteger
import java.net.{InetAddress, InetSocketAddress}

import akka.util.ByteString
import sttp.tapir.Schema
import sttp.tapir.SchemaType.{SArray, SInteger, SString}

import org.alephium.api.model.{Amount, Script}
import org.alephium.crypto.wallet.Mnemonic
import org.alephium.protocol.{BlockHash, Hash, PublicKey, Signature}
import org.alephium.protocol.model.{Address, CliqueId, GroupIndex}
import org.alephium.protocol.vm.{GasBox, GasPrice, LockupScript, StatefulContract}
import org.alephium.util.{AVector, TimeStamp, U256}

trait TapirSchemasLike {
  implicit def avectorSchema[T: Schema]: Schema[AVector[T]] = Schema(
    SArray(implicitly[Schema[T]])(_.toIterable)
  )
  implicit val addressSchema: Schema[Address]                     = Schema(SString())
  implicit val addressAssetSchema: Schema[Address.Asset]          = Schema(SString())
  implicit val addressContractSchema: Schema[Address.Contract]    = Schema(SString())
  implicit val byteStringSchema: Schema[ByteString]               = Schema(SString())
  implicit val pulblicKeySchema: Schema[PublicKey]                = Schema(SString())
  implicit val groupIndexSchema: Schema[GroupIndex]               = Schema(SInteger())
  implicit val hashSchema: Schema[Hash]                           = Schema(SString())
  implicit val blockHashSchema: Schema[BlockHash]                 = Schema(SString())
  implicit val pubScriptSchema: Schema[LockupScript]              = Schema(SString())
  implicit val ScriptSchema: Schema[Script]                       = Schema(SString())
  implicit val signatureSchema: Schema[Signature]                 = Schema(SString())
  implicit val timestampSchema: Schema[TimeStamp]                 = Schema(SInteger()).format("int64")
  implicit val u256Schema: Schema[U256]                           = Schema(SString()).format("uint256")
  implicit val amountSchema: Schema[Amount]                       = Schema(SString()).format("uint256")
  implicit val amountHintSchema: Schema[Amount.Hint]              = Schema(SString()).format("x.x ALPH")
  implicit val gasBoxSchema: Schema[GasBox]                       = Schema(SInteger())
  implicit val gasPriceSchema: Schema[GasPrice]                   = Schema(SString()).format("uint256")
  implicit val bigIntegerSchema: Schema[BigInteger]               = Schema(SString()).format("bigint")
  implicit val inetAddressSchema: Schema[InetAddress]             = Schema(SString())
  implicit val inetSocketAddressSchema: Schema[InetSocketAddress] = Schema(SString())
  implicit val cliqueIdSchema: Schema[CliqueId]                   = Schema(SString())
  implicit val mnemonicSchema: Schema[Mnemonic]                   = Schema(SString())
  implicit val mnemonicSizeSchema: Schema[Mnemonic.Size]          = Schema(SInteger())

  implicit val statefulContractSchema: Schema[StatefulContract] = Schema(SString())
}

object TapirSchemas extends TapirSchemasLike
