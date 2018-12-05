package org.alephium.crypto

import akka.util.ByteString
import org.bouncycastle.crypto.Digest
import org.bouncycastle.crypto.digests.SHA256Digest

case class Sha256(bytes: ByteString) extends HashOutput

object Sha256 extends Hash[Sha256] {
  override val size: Int = 32

  override val provider: Digest = new SHA256Digest()

  private def apply(digest: ByteString): Sha256 = {
    require(digest.length == size)
    new Sha256(digest)
  }

  override def unsafeFrom(digest: ByteString): Sha256 = apply(digest)
}
