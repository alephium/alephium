package org.alephium.wallet.tapir

import sttp.tapir.Schema

import org.alephium.crypto.wallet.Mnemonic
import org.alephium.protocol.model.Address

trait Schemas {
  implicit val addressSchema: Schema[Address] = Schema(Schema.schemaForString.schemaType)

  implicit val mnemonicSizeSchema: Schema[Mnemonic.Size] = Schema(Schema.schemaForInt.schemaType)
}
