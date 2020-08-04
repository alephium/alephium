package org.alephium

import org.alephium.crypto.Blake2b

package object protocol {
  type Hash = Blake2b
  val Hash: Blake2b.type = Blake2b
}
