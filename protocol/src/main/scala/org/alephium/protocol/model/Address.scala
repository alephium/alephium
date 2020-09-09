package org.alephium.protocol.model

import akka.util.ByteString

import org.alephium.protocol.config.GroupConfig
import org.alephium.protocol.model.{GroupIndex}
import org.alephium.protocol.vm.LockupScript
import org.alephium.serde.{deserialize, serialize}
import org.alephium.util.Base58

final case class Address(lockupScript: LockupScript) extends AnyVal {

  def toBase58(networkType: NetworkType): String =
    Base58.encode(
      LockupScript.withPrefix(serialize(lockupScript))(b => (b + networkType.prefix).toByte))

  def groupIndex(implicit config: GroupConfig): GroupIndex = lockupScript.groupIndex
}

object Address {
  def fromBase58(input: String, networkType: NetworkType): Option[Address] = {
    Base58
      .decode(input)
      .flatMap(data =>
        deserialize[LockupScript](withPrefix(data)(b => (b - networkType.prefix).toByte)).toOption)
      .map(Address(_))
  }

  private def withPrefix(data: ByteString)(f: Byte => Byte): ByteString = {
    data.headOption match {
      case Some(prefix) =>
        ByteString.fromArrayUnsafe(data.updated(0, f(prefix).toByte).toArray)
      case None => data
    }
  }
}
