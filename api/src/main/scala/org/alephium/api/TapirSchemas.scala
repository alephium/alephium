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

import scala.reflect.ClassTag

import akka.util.ByteString
import sttp.tapir.Schema
import sttp.tapir.SchemaType.SInteger

import org.alephium.crypto.wallet.Mnemonic
import org.alephium.protocol.{BlockHash, Hash, PublicKey, Signature}
import org.alephium.protocol.model.{Address, CliqueId, GroupIndex}
import org.alephium.protocol.vm.LockupScript
import org.alephium.util.{AVector, TimeStamp, U256}

trait TapirSchemasLike {
  implicit def avectorSchema[T: Schema: ClassTag]: Schema[AVector[T]] =
    implicitly[Schema[T]].asArray.map(array => Some(AVector.from(array)))(_.toArray)
  implicit val addressSchema: Schema[Address]         = Schema(Schema.schemaForString.schemaType)
  implicit val byteStringSchema: Schema[ByteString]   = Schema(Schema.schemaForString.schemaType)
  implicit val pulblicKeySchema: Schema[PublicKey]    = Schema(Schema.schemaForString.schemaType)
  implicit val groupIndexSchema: Schema[GroupIndex]   = Schema(Schema.schemaForInt.schemaType)
  implicit val hashSchema: Schema[Hash]               = Schema(Schema.schemaForString.schemaType)
  implicit val blockHashSchema: Schema[BlockHash]     = Schema(Schema.schemaForString.schemaType)
  implicit val pubScriptSchema: Schema[LockupScript]  = Schema(Schema.schemaForString.schemaType)
  implicit val signatureSchema: Schema[Signature]     = Schema(Schema.schemaForString.schemaType)
  implicit val timestampSchema: Schema[TimeStamp]     = Schema(Schema.schemaForLong.schemaType)
  implicit val u256Schema: Schema[U256]               = Schema(SInteger).format("uint256")
  implicit val bigIntegerSchema: Schema[BigInteger]   = Schema(SInteger).format("bigint")
  implicit val inetAddressSchema: Schema[InetAddress] = Schema(Schema.schemaForString.schemaType)
  implicit val inetSocketAddressSchema: Schema[InetSocketAddress] = Schema(
    Schema.schemaForString.schemaType
  )
  implicit val cliqueIdSchema: Schema[CliqueId]          = Schema(Schema.schemaForString.schemaType)
  implicit val mnemonicSchema: Schema[Mnemonic]          = Schema(Schema.schemaForString.schemaType)
  implicit val mnemonicSizeSchema: Schema[Mnemonic.Size] = Schema(Schema.schemaForInt.schemaType)
}

object TapirSchemas extends TapirSchemasLike
