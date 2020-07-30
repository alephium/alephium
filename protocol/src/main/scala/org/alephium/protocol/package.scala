package org.alephium

import org.alephium.crypto.Keccak256

package object protocol {
  type Hash = Keccak256
  val Hash: Keccak256.type = Keccak256
}
