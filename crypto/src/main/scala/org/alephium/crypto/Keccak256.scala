package org.alephium.crypto

import akka.util.ByteString
import org.bouncycastle.crypto.Digest
import org.bouncycastle.crypto.digests.KeccakDigest

import org.alephium.serde.RandomBytes

class Keccak256(val bytes: ByteString) extends RandomBytes

object Keccak256
    extends HashCompanion[Keccak256](bs => {
      assert(bs.size == keccak256Length)
      new Keccak256(bs)
    }, _.bytes) {

  override def length: Int = keccak256Length

  // TODO: optimize with queue of providers
  override def provider: Digest = new KeccakDigest(length * 8)
}
