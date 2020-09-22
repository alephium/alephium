package org.alephium.protocol.model

import pureconfig.ConfigReader
import pureconfig.generic.semiauto.deriveEnumerationReader

import org.alephium.util.AVector

sealed trait NetworkType {
  def prefix: String
}

object NetworkType {
  case object Mainnet extends NetworkType {
    val prefix: String = "M"
  }
  case object Testnet extends NetworkType {
    val prefix: String = "T"
  }
  case object Devnet extends NetworkType {
    val prefix: String = "D"
  }

  val all: AVector[NetworkType] = AVector(Mainnet, Testnet, Devnet)

  implicit val networkTypeReader: ConfigReader[NetworkType] = deriveEnumerationReader[NetworkType]

  def decode(address: String): Option[(NetworkType, String)] = {
    if (address.startsWith(Mainnet.prefix)) {
      Some(Mainnet -> address.drop(Mainnet.prefix.length))
    } else if (address.startsWith(Testnet.prefix)) {
      Some(Testnet -> address.drop(Testnet.prefix.length))
    } else if (address.startsWith(Devnet.prefix)) {
      Some(Devnet -> address.drop(Devnet.prefix.length))
    } else None
  }
}
