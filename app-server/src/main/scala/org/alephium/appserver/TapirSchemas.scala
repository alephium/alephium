package org.alephium.appserver

import akka.util.ByteString
import sttp.tapir.Schema

import org.alephium.protocol.ALF.Hash
import org.alephium.protocol.vm.LockupScript
import org.alephium.util.{AVector, TimeStamp}

object TapirSchemas {
  implicit val hashSchema: Schema[Hash]                     = Schema(Schema.schemaForString.schemaType)
  implicit def avectorSchema[T: Schema]: Schema[AVector[T]] = implicitly[Schema[T]].asArrayElement
  implicit val timestampSchema: Schema[TimeStamp]           = Schema(Schema.schemaForLong.schemaType)
  implicit val pubScriptSchema: Schema[LockupScript]        = Schema(Schema.schemaForString.schemaType)
  implicit val byteStringSchema: Schema[ByteString]         = Schema(Schema.schemaForString.schemaType)
}
