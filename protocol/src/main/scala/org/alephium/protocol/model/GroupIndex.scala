package org.alephium.protocol.model

import scala.annotation.tailrec

import org.alephium.crypto._
import org.alephium.protocol.ALF.Hash
import org.alephium.protocol.config.GroupConfig
import org.alephium.protocol.vm.LockupScript

class GroupIndex(val value: Int) extends AnyVal {
  override def toString: String = s"GroupIndex($value)"

  @tailrec
  final def generateKey(implicit config: GroupConfig): (ED25519PrivateKey, ED25519PublicKey) = {
    val (privateKey, publicKey) = ED25519.generatePriPub()
    val lockupScript            = LockupScript.p2pkh(Hash.hash(publicKey.bytes))
    if (lockupScript.groupIndex == this) (privateKey, publicKey)
    else generateKey
  }
}

object GroupIndex {
  def unsafe(group: Int)(implicit config: GroupConfig): GroupIndex = {
    assume(validate(group))
    new GroupIndex(group)
  }

  def from(group: Int)(implicit config: GroupConfig): Option[GroupIndex] = {
    if (validate(group)) {
      Some(new GroupIndex(group))
    } else {
      None
    }
  }

  @inline
  private def validate(group: Int)(implicit config: GroupConfig): Boolean =
    0 <= group && group < config.groups
}
