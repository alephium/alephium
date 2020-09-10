package org.alephium.wallet.tapir

import sttp.tapir.Schema

import org.alephium.protocol.model.Address

trait Schemas {
  implicit val addressSchema: Schema[Address] = Schema(Schema.schemaForString.schemaType)
}
