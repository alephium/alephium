package org.alephium.flow

import scala.language.implicitConversions

import org.alephium.util.U64

trait U64Helpers {
  implicit class U64Helpers(n: U64) { Self =>
    def +(m: Int): U64  = Self + m.toLong
    def +(m: Long): U64 = n.add(U64.from(m).get).get
    def -(m: Int): U64  = Self - m.toLong
    def -(m: Long): U64 = n.sub(U64.from(m).get).get
  }

  implicit def intToU64(n: Int): U64   = U64.unsafe(n.toLong)
  implicit def longToU64(n: Long): U64 = U64.unsafe(n)
}
