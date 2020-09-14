package org.alephium.protocol.model

import org.alephium.protocol.PublicKey
import org.alephium.protocol.config.GroupConfig
import org.alephium.protocol.vm.LockupScript
import org.alephium.serde.{deserialize, serialize}
import org.alephium.util.Base58

final case class Address(networkType: NetworkType, lockupScript: LockupScript) {
  def toBase58: String = networkType.prefix ++ Base58.encode(serialize(lockupScript))

  def groupIndex(implicit config: GroupConfig): GroupIndex = lockupScript.groupIndex
}

object Address {
  def fromBase58(input: String, expected: NetworkType): Option[Address] = {
    for {
      (networkType, lockupScriptBase58) <- NetworkType.decode(input)
      if networkType == expected
      lockupScriptRaw <- Base58.decode(lockupScriptBase58)
      lockupScript    <- deserialize[LockupScript](lockupScriptRaw).toOption
    } yield Address(networkType, lockupScript)
  }

  def p2pkh(networkType: NetworkType, publicKey: PublicKey): Address =
    Address(networkType, LockupScript.p2pkh(publicKey))
}
