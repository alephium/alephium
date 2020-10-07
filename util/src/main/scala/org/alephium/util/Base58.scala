// Copyright 2018 The Alephium Authors
// This file is part of the alephium project.
//
// The library is free software: you can redistribute it and/or modify
// it under the terms of the GNU Lesser General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// The library is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
// GNU Lesser General Public License for more details.
//
// You should have received a copy of the GNU Lesser General Public License
// along with the library. If not, see <http://www.gnu.org/licenses/>.

package org.alephium.util

import scala.annotation.tailrec

import akka.util.ByteString

// scalastyle:off magic.number return
object Base58 {
  val alphabet: String = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
  private val toBase58 = Array(
    0, 1, 2, 3, 4, 5, 6, 7, 8, -1, -1, -1, -1, -1, -1, -1, 9, 10, 11, 12, 13, 14, 15, 16, -1, 17,
    18, 19, 20, 21, -1, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32, -1, -1, -1, -1, -1, -1, 33, 34,
    35, 36, 37, 38, 39, 40, 41, 42, 43, -1, 44, 45, 46, 47, 48, 49, 50, 51, 52, 53, 54, 55, 56, 57
  )

  private def toBase58(c: Char): Int = {
    val x = c.toInt
    if (x < 49) -1 else if (x <= 122) toBase58(x - 49) else -1
  }

  private val zero = BigInt(0)
  private val base = BigInt(58)

  @inline private def count(f: => Int, length: Int): Int = f match {
    case -1 => length
    case n  => n
  }

  def encode(bs: ByteString): String = {
    if (bs.isEmpty) ""
    else {
      val array  = bs.toArray
      val nZeros = count(array.indexWhere(_ != 0), array.length)
      val prefix = Array.fill(nZeros)(alphabet(0))

      val stringBuilder = new StringBuilder()
      @tailrec
      def iter(value: BigInt): Unit = {
        if (value != zero) {
          val (div, rem) = value /% base
          stringBuilder.append(alphabet(rem.intValue))
          iter(div)
        } else stringBuilder.reverseInPlace()
      }
      iter(BigInt(1, array))

      stringBuilder.insertAll(0, prefix).toString
    }
  }

  def decode(input: String): Option[ByteString] = {
    val zeroLength = count(input.indexWhere(_ != '1'), input.length)
    val zeros      = ByteString.fromArrayUnsafe(Array.fill(zeroLength)(0))
    val trim       = input.drop(zeroLength)

    val decodedBi = trim.foldLeft(zero) { (bi, c) =>
      val n = toBase58(c)
      if (n == -1) return None else (bi * base + n)
    }

    if (decodedBi == zero) Some(zeros)
    else {
      Some(zeros ++ ByteString.fromArrayUnsafe(decodedBi.toByteArray).dropWhile(_ == 0))
    }
  }
}
