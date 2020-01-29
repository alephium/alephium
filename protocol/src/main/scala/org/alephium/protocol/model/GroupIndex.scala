package org.alephium.protocol.model

import scala.annotation.tailrec

import org.alephium.crypto._
import org.alephium.protocol.config.GroupConfig
import org.alephium.protocol.script.PubScript

class GroupIndex private (val value: Int) extends AnyVal {
  override def toString: String = s"GroupIndex($value)"

  @tailrec
  final def generateP2pkhKey()(
      implicit config: GroupConfig): (ED25519PrivateKey, ED25519PublicKey) = {
    val (privateKey, publicKey) = ED25519.generatePriPub()
    val pubScript               = PubScript.p2pkh(publicKey)
    if (GroupIndex.from(pubScript) == this) (privateKey, publicKey)
    else generateP2pkhKey()
  }
}

object GroupIndex {
  def apply(value: Int)(implicit config: GroupConfig): GroupIndex = {
    require(validate(value))
    new GroupIndex(value)
  }

  def fromP2PKH(publicKey: ED25519PublicKey)(implicit config: GroupConfig): GroupIndex = {
    val pubScript = PubScript.p2pkh(publicKey)
    from(pubScript)
  }

  def from(pubScript: PubScript)(implicit config: GroupConfig): GroupIndex = {
    val bytes = pubScript.hash.bytes
    GroupIndex((bytes.last & 0xFF) % config.groups)
  }

  def unsafe(value: Int): GroupIndex = new GroupIndex(value)
  def validate(group: Int)(implicit config: GroupConfig): Boolean =
    0 <= group && group < config.groups
}
