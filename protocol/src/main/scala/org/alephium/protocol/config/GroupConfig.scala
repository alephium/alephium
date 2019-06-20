package org.alephium.protocol.config

import org.alephium.crypto.{ED25519, ED25519PrivateKey, ED25519PublicKey}
import org.alephium.protocol.model.GroupIndex

import scala.annotation.tailrec

object GroupConfig {
  def generateKeyForGroup(groupIndex: GroupIndex)(
      implicit config: GroupConfig): (ED25519PrivateKey, ED25519PublicKey) = {
    @tailrec
    def iter(): (ED25519PrivateKey, ED25519PublicKey) = {
      val keyPair   = ED25519.generatePriPub()
      val publicKey = keyPair._2
      val keyIndex  = GroupIndex((publicKey.bytes.last & 0xFF) % config.groups)
      if (keyIndex == groupIndex) keyPair else iter()
    }
    iter()
  }
}

trait GroupConfig {
  def groups: Int

  def chainNum: Int = groups * groups

  def depsNum: Int = 2 * groups - 1
}
