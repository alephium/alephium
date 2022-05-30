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

  implicit def optionAvectorSchema[T: Schema]: Schema[Option[AVector[T]]] = Schema.schemaForOption

  implicit val addressSchema: Schema[Address]                  = Schema(SString()).format("address")
  implicit val addressAssetSchema: Schema[Address.Asset]       = Schema(SString()).format("address")
  implicit val addressContractSchema: Schema[Address.Contract] = Schema(SString()).format("address")
  implicit val byteStringSchema: Schema[ByteString]   = Schema(SString()).format("hex-string")
  implicit val pulblicKeySchema: Schema[PublicKey]    = Schema(SString()).format("public-key")
  implicit val groupIndexSchema: Schema[GroupIndex]   = Schema(SInteger()).format("group-index")
  implicit val hashSchema: Schema[Hash]               = Schema(SString()).format("32-byte-hash")
  implicit val blockHashSchema: Schema[BlockHash]     = Schema(SString()).format("block-hash")
  implicit val pubScriptSchema: Schema[LockupScript]  = Schema(SString()).format("script")
  implicit val ScriptSchema: Schema[Script]           = Schema(SString()).format("script")
  implicit val signatureSchema: Schema[Signature]     = Schema(SString()).format("signature")
  implicit val timestampSchema: Schema[TimeStamp]     = Schema(SInteger()).format("int64")
  implicit val u256Schema: Schema[U256]               = Schema(SString()).format("uint256")
  implicit val amountSchema: Schema[Amount]           = Schema(SString()).format("uint256")
  implicit val amountHintSchema: Schema[Amount.Hint]  = Schema(SString()).format("x.x ALPH")
  implicit val gasBoxSchema: Schema[GasBox]           = Schema(SInteger()).format("gas")
  implicit val gasPriceSchema: Schema[GasPrice]       = Schema(SString()).format("uint256")
  implicit val bigIntegerSchema: Schema[BigInteger]   = Schema(SString()).format("bigint")
  implicit val inetAddressSchema: Schema[InetAddress] = Schema(SString()).format("inet-address")
  implicit val inetSocketAddressSchema: Schema[InetSocketAddress] =
    Schema(SString()).format("inet-socket-address")
  implicit val cliqueIdSchema: Schema[CliqueId]          = Schema(SString()).format("clique-id")
  implicit val mnemonicSchema: Schema[Mnemonic]          = Schema(SString())
  implicit val mnemonicSizeSchema: Schema[Mnemonic.Size] = Schema(SInteger())

  implicit val statefulContractSchema: Schema[StatefulContract] =
    Schema(SString()).format("contract")
}

object TapirSchemas extends TapirSchemasLike
