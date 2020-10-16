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

package org.alephium.appserver

import akka.util.ByteString
import sttp.tapir.Schema
import sttp.tapir.SchemaType.SInteger

import org.alephium.protocol.{Hash, Signature}
import org.alephium.protocol.model.{Address, GroupIndex}
import org.alephium.protocol.vm.LockupScript
import org.alephium.util.{AVector, TimeStamp, U256}

object TapirSchemas {
  implicit def avectorSchema[T: Schema]: Schema[AVector[T]] = implicitly[Schema[T]].asArrayElement
  implicit val addressSchema: Schema[Address]               = Schema(Schema.schemaForString.schemaType)
  implicit val byteStringSchema: Schema[ByteString]         = Schema(Schema.schemaForString.schemaType)
  implicit val groupIndexSchema: Schema[GroupIndex]         = Schema(Schema.schemaForInt.schemaType)
  implicit val hashSchema: Schema[Hash]                     = Schema(Schema.schemaForString.schemaType)
  implicit val pubScriptSchema: Schema[LockupScript]        = Schema(Schema.schemaForString.schemaType)
  implicit val signatureSchema: Schema[Signature]           = Schema(Schema.schemaForString.schemaType)
  implicit val timestampSchema: Schema[TimeStamp]           = Schema(Schema.schemaForLong.schemaType)
  implicit val u256Schema: Schema[U256]                     = Schema(SInteger).format("uint256")
}
