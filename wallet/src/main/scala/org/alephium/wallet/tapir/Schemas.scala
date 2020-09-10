package org.alephium.wallet.tapir

import sttp.tapir.Schema

import org.alephium.crypto.wallet.Mnemonic
import org.alephium.protocol.Hash
import org.alephium.protocol.model.Address
import org.alephium.util.U64

trait Schemas {
  implicit val addressSchema: Schema[Address] = Schema(Schema.schemaForString.schemaType)

  implicit val hashSchema: Schema[Hash] = Schema(Schema.schemaForString.schemaType)

  implicit val mnemonicSizeSchema: Schema[Mnemonic.Size] = Schema(Schema.schemaForInt.schemaType)

  implicit val u64Schema: Schema[U64] = Schema(Schema.schemaForLong.schemaType)
}
