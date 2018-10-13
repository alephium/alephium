package org.alephium.crypto

import akka.util.ByteString
import org.bouncycastle.crypto.Digest
import org.bouncycastle.crypto.digests.SHA256Digest

import org.alephium.serde.RandomBytes

class Sha256(val bytes: ByteString) extends RandomBytes

object Sha256
    extends HashCompanion[Sha256](bs => {
      assert(bs.size == sha256Length)
      new Sha256(bs)
    }, _.bytes) {

  override def length: Int = sha256Length

  // TODO: optimize with queue of providers
  override def provider: Digest = new SHA256Digest()
}
