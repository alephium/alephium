package org.alephium.appserver

import akka.util.ByteString
import sttp.tapir.Schema

import org.alephium.protocol.{Hash, Signature}
import org.alephium.protocol.model.{Address, GroupIndex}
import org.alephium.protocol.vm.LockupScript
import org.alephium.util.{AVector, TimeStamp}

object TapirSchemas {
  implicit def avectorSchema[T: Schema]: Schema[AVector[T]] = implicitly[Schema[T]].asArrayElement
  implicit val addressSchema: Schema[Address]               = Schema(Schema.schemaForString.schemaType)
  implicit val byteStringSchema: Schema[ByteString]         = Schema(Schema.schemaForString.schemaType)
  implicit val groupIndexSchema: Schema[GroupIndex]         = Schema(Schema.schemaForInt.schemaType)
  implicit val hashSchema: Schema[Hash]                     = Schema(Schema.schemaForString.schemaType)
  implicit val pubScriptSchema: Schema[LockupScript]        = Schema(Schema.schemaForString.schemaType)
  implicit val signatureSchema: Schema[Signature]           = Schema(Schema.schemaForString.schemaType)
  implicit val timestampSchema: Schema[TimeStamp]           = Schema(Schema.schemaForLong.schemaType)
}
