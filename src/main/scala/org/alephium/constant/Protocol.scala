package org.alephium.constant

import org.alephium.crypto.{ED25519PrivateKey, ED25519PublicKey, ED25519Signature}
import org.alephium.protocol.model._
import org.alephium.util.Hex

object Protocol {
  val version = 0

  object Genesis {
    // scalastyle:off magic.number
    val privateKey: ED25519PrivateKey = ED25519PrivateKey.unsafeFrom(
      Hex("604b105965f2bb262d5bede6f9790c7ba9ca08c0f31627ec24f52b67b59dfa65"))
    val publicKey: ED25519PublicKey = ED25519PublicKey.unsafeFrom(
      Hex("2db399c90fee96ec2310b62e3f62b5bd87972a96e5fa64675f0adc683546cd1c"))

    val unsigned     = UnsignedTransaction(Seq.empty, Seq(TxOutput(100, publicKey)))
    val transaction  = Transaction(unsigned, ED25519Signature.zero)
    val block: Block = Block.from(Seq.empty, Seq(transaction))
    // scalastyle:on
  }
}
