package org.alephium.protocol.model

import pureconfig.ConfigReader
import pureconfig.generic.semiauto.deriveEnumerationReader

sealed trait NetworkType {
  def prefix: Byte
}

object NetworkType {
  case object Mainnet extends NetworkType {
    val prefix: Byte = 0.toByte
  }
  implicit val networkTypeReader: ConfigReader[NetworkType] = deriveEnumerationReader[NetworkType]
}
