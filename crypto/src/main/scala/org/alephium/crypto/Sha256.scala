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

package org.alephium.crypto

import akka.util.ByteString
import org.bouncycastle.crypto.Digest
import org.bouncycastle.crypto.digests.SHA256Digest

import org.alephium.serde.RandomBytes

class Sha256(val bytes: ByteString) extends RandomBytes

object Sha256 extends HashSchema[Sha256](HashSchema.unsafeSha256, _.bytes) {
  override def length: Int = 32

  // TODO: optimize with queue of providers
  override def provider: Digest = new SHA256Digest()
}
