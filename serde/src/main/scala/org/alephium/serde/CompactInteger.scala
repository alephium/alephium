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

package org.alephium.serde

import akka.util.ByteString

import org.alephium.util.{Bytes, I256, U256, U32}

//scalastyle:off magic.number

// The design is heavily influenced by Polkadot's SCALE Codec
@SuppressWarnings(Array("org.wartremover.warts.IterableOps"))
object CompactInteger {
  /*
   * unsigned integers are encoded with the first two most significant bits denoting the mode:
   * - 0b00: single-byte mode; [0, 2**6)
   * - 0b01: two-byte mode; [0, 2**14)
   * - 0b10: four-byte mode; [0, 2**30)
   * - 0b11: multi-byte mode: [0, 2**536)
   */
  object Unsigned {
    private val oneByteBound  = 0x40 // 0b01000000
    private val twoByteBound  = oneByteBound << 8
    private val fourByteBound = oneByteBound << (8 * 3)

    def encode(n: U32): ByteString = {
      if (n < U32.unsafe(oneByteBound)) {
        ByteString((n.v + SingleByte.prefix).toByte)
      } else if (n < U32.unsafe(twoByteBound)) {
        ByteString(((n.v >> 8) + TwoByte.prefix).toByte, n.v.toByte)
      } else if (n < U32.unsafe(fourByteBound)) {
        ByteString(
          ((n.v >> 24) + FourByte.prefix).toByte,
          (n.v >> 16).toByte,
          (n.v >> 8).toByte,
          n.v.toByte
        )
      } else {
        ByteString(
          MultiByte.prefix.toByte,
          (n.v >> 24).toByte,
          (n.v >> 16).toByte,
          (n.v >> 8).toByte,
          n.v.toByte
        )
      }
    }

    def encode(n: U256): ByteString = {
      if (n < U256.unsafe(fourByteBound)) {
        encode(U32.unsafe(n.v.intValue()))
      } else {
        val data = {
          val tmp = n.v.toByteArray
          if (tmp(0) == 0x00) tmp.tail else tmp
        }
        val header = ((data.length - 4) + MultiByte.prefix).toByte
        val body   = ByteString.fromArrayUnsafe(data)
        ByteString(header) ++ body
      }
    }

    def decodeU32(bs: ByteString): SerdeResult[Staging[U32]] = {
      for {
        tuple  <- Mode.decode(bs)
        result <- decodeU32(tuple._1, tuple._2, tuple._3)
      } yield result
    }

    private def decodeU32(
        mode: Mode,
        body: ByteString,
        rest: ByteString
    ): SerdeResult[Staging[U32]] = {
      mode match {
        case m: FixedWidth =>
          decodeInt(m, body, rest).map(_.mapValue(U32.unsafe))
        case MultiByte =>
          assume(body.length >= 5)
          if (body.length == 5) {
            val value = Bytes.toIntUnsafe(body.tail)
            Right(Staging(U32.unsafe(value), rest))
          } else {
            Left(
              SerdeError.wrongFormat(s"Expect 4 bytes int, but get ${body.length - 1} bytes int")
            )
          }
      }
    }

    private def decodeInt(
        mode: FixedWidth,
        body: ByteString,
        rest: ByteString
    ): SerdeResult[Staging[Int]] = {
      mode match {
        case SingleByte =>
          Right(Staging(body(0).toInt, rest))
        case TwoByte =>
          assume(body.length == 2)
          val value = ((body(0) & Mode.maskMode) << 8) | (body(1) & 0xff)
          Right(Staging(value, rest))
        case FourByte =>
          assume(body.length == 4)
          val value = ((body(0) & Mode.maskMode) << 24) |
            ((body(1) & 0xff) << 16) |
            ((body(2) & 0xff) << 8) |
            body(3) & 0xff
          Right(Staging(value, rest))
      }
    }

    def decodeU256(bs: ByteString): SerdeResult[Staging[U256]] = {
      for {
        tuple  <- Mode.decode(bs)
        result <- decodeU256(tuple._1, tuple._2, tuple._3)
      } yield result
    }

    private def decodeU256(
        mode: Mode,
        body: ByteString,
        rest: ByteString
    ): SerdeResult[Staging[U256]] = {
      mode match {
        case m: FixedWidth =>
          decodeInt(m, body, rest).map(_.mapValue(n => U256.unsafe(Integer.toUnsignedLong(n))))
        case MultiByte =>
          U256.from(body.tail) match {
            case Some(n) => Right(Staging(n, rest))
            case None    => Left(SerdeError.validation(s"Expect U256, but get $body"))
          }
      }
    }
  }

  /*
   * signed integers are encoded with the first two most significant bits denoting the mode:
   * - 0b00: single-byte mode; [-2**5, 2**5)
   * - 0b01: two-byte mode; [-2**13, 2**13)
   * - 0b10: four-byte mode; [-2**29, 2**29)
   * - 0b11: multi-byte mode: [-2**535, 2**535)
   */
  object Signed {
    private val signFlag: Int      = 0x20 // 0b00100000
    private val oneByteBound: Int  = 0x20 // 0b00100000
    private val twoByteBound: Int  = oneByteBound << 8
    private val fourByteBound: Int = oneByteBound << (8 * 3)

    def encode(n: Int): ByteString = {
      if (n >= 0) {
        encodePositiveInt(n)
      } else {
        encodeNegativeInt(n)
      }
    }

    @inline private def encodePositiveInt(n: Int): ByteString = {
      if (n < oneByteBound) {
        ByteString((n + SingleByte.prefix).toByte)
      } else if (n < twoByteBound) {
        ByteString(((n >> 8) + TwoByte.prefix).toByte, n.toByte)
      } else if (n < fourByteBound) {
        ByteString(
          ((n >> 24) + FourByte.prefix).toByte,
          (n >> 16).toByte,
          (n >> 8).toByte,
          n.toByte
        )
      } else {
        ByteString(
          MultiByte.prefix.toByte,
          (n >> 24).toByte,
          (n >> 16).toByte,
          (n >> 8).toByte,
          n.toByte
        )
      }
    }

    @inline private def encodeNegativeInt(n: Int): ByteString = {
      if (n >= -oneByteBound) {
        ByteString((n ^ SingleByte.negPrefix).toByte)
      } else if (n >= -twoByteBound) {
        ByteString(((n >> 8) ^ TwoByte.negPrefix).toByte, n.toByte)
      } else if (n >= -fourByteBound) {
        ByteString(
          ((n >> 24) ^ FourByte.negPrefix).toByte,
          (n >> 16).toByte,
          (n >> 8).toByte,
          n.toByte
        )
      } else {
        ByteString(
          MultiByte.prefix.toByte,
          (n >> 24).toByte,
          (n >> 16).toByte,
          (n >> 8).toByte,
          n.toByte
        )
      }
    }

    def encode(n: Long): ByteString = {
      if (n >= -0x20000000 && n < 0x20000000) {
        encode(n.toInt)
      } else {
        ByteString(
          (4 | MultiByte.prefix).toByte,
          (n >> 56).toByte,
          (n >> 48).toByte,
          (n >> 40).toByte,
          (n >> 32).toByte,
          (n >> 24).toByte,
          (n >> 16).toByte,
          (n >> 8).toByte,
          n.toByte
        )
      }
    }

    def encode(n: I256): ByteString = {
      if (n >= I256.from(-0x20000000) && n < I256.from(0x20000000)) {
        encode(n.v.intValue())
      } else {
        val data   = n.v.toByteArray
        val header = ((data.length - 4) + MultiByte.prefix).toByte
        val body   = ByteString.fromArrayUnsafe(data)
        ByteString(header) ++ body
      }
    }

    def decodeInt(bs: ByteString): SerdeResult[Staging[Int]] = {
      for {
        tuple  <- Mode.decode(bs)
        result <- decodeInt(tuple._1, tuple._2, tuple._3)
      } yield result
    }

    private def decodeInt(
        mode: Mode,
        body: ByteString,
        rest: ByteString
    ): SerdeResult[Staging[Int]] = {
      mode match {
        case m: FixedWidth =>
          decodeInt(m, body, rest)
        case MultiByte =>
          assume(body.length >= 5)
          if (body.length == 5) {
            val value = Bytes.toIntUnsafe(body.tail)
            Right(Staging(value, rest))
          } else {
            Left(
              SerdeError.wrongFormat(s"Expect 4 bytes int, but get ${body.length - 1} bytes int")
            )
          }
      }
    }

    private def decodeInt(
        mode: FixedWidth,
        body: ByteString,
        rest: ByteString
    ): SerdeResult[Staging[Int]] = {
      val isPositive = (body(0) & signFlag) == 0
      if (isPositive) {
        decodePositiveInt(mode, body, rest)
      } else {
        decodeNegativeInt(mode, body, rest)
      }
    }

    private def decodePositiveInt(
        mode: FixedWidth,
        body: ByteString,
        rest: ByteString
    ): SerdeResult[Staging[Int]] = {
      mode match {
        case SingleByte =>
          Right(Staging(body(0).toInt, rest))
        case TwoByte =>
          assume(body.length == 2)
          val value = ((body(0) & Mode.maskMode) << 8) | (body(1) & 0xff)
          Right(Staging(value, rest))
        case FourByte =>
          assume(body.length == 4)
          val value = ((body(0) & Mode.maskMode) << 24) |
            ((body(1) & 0xff) << 16) |
            ((body(2) & 0xff) << 8) |
            body(3) & 0xff
          Right(Staging(value, rest))
      }
    }

    private def decodeNegativeInt(
        mode: FixedWidth,
        body: ByteString,
        rest: ByteString
    ): SerdeResult[Staging[Int]] = {
      mode match {
        case SingleByte =>
          Right(Staging(body(0).toInt | Mode.maskModeNeg, rest))
        case TwoByte =>
          assume(body.length == 2)
          val value = ((body(0) | Mode.maskModeNeg) << 8) | (body(1) & 0xff)
          Right(Staging(value, rest))
        case FourByte =>
          assume(body.length == 4)
          val value = ((body(0) | Mode.maskModeNeg) << 24) |
            ((body(1) & 0xff) << 16) |
            ((body(2) & 0xff) << 8) |
            body(3) & 0xff
          Right(Staging(value, rest))
      }
    }

    def decodeLong(bs: ByteString): SerdeResult[Staging[Long]] = {
      for {
        tuple  <- Mode.decode(bs)
        result <- decodeLong(tuple._1, tuple._2, tuple._3)
      } yield result
    }

    private def decodeLong(
        mode: Mode,
        body: ByteString,
        rest: ByteString
    ): SerdeResult[Staging[Long]] = {
      mode match {
        case m: FixedWidth =>
          decodeInt(m, body, rest).map(_.mapValue(_.toLong))
        case MultiByte =>
          assume(body.length >= 5)
          if (body.length == 9) {
            val value = Bytes.toLongUnsafe(body.tail)
            Right(Staging(value, rest))
          } else {
            Left(
              SerdeError.wrongFormat(s"Expect 9 bytes long, but get ${body.length - 1} bytes long")
            )
          }
      }
    }

    def decodeI256(bs: ByteString): SerdeResult[Staging[I256]] = {
      for {
        tuple  <- Mode.decode(bs)
        result <- decodeI256(tuple._1, tuple._2, tuple._3)
      } yield result
    }

    private def decodeI256(
        mode: Mode,
        body: ByteString,
        rest: ByteString
    ): SerdeResult[Staging[I256]] = {
      mode match {
        case m: FixedWidth =>
          decodeInt(m, body, rest).map(_.mapValue(n => I256.from(n)))
        case MultiByte =>
          I256.from(body.tail) match {
            case Some(n) => Right(Staging(n, rest))
            case None    => Left(SerdeError.validation(s"Expect I256, but get $body"))
          }
      }
    }
  }

  sealed trait Mode {
    def prefix: Int
    def negPrefix: Int
  }

  object Mode {
    val maskMode: Int    = 0x3f
    val maskRest: Int    = 0xc0
    val maskModeNeg: Int = 0xffffffc0

    def decode(bs: ByteString): SerdeResult[(Mode, ByteString, ByteString)] = {
      if (bs.isEmpty) {
        Left(SerdeError.incompleteData(1, 0))
      } else {
        (bs(0) & maskRest) match {
          case SingleByte.prefix => Right((SingleByte, bs.take(1), bs.drop(1)))
          case TwoByte.prefix    => checkSize(bs, 2, TwoByte)
          case FourByte.prefix   => checkSize(bs, 4, FourByte)
          case _                 => checkSize(bs, (bs(0) & Mode.maskMode) + 4 + 1, MultiByte)
        }
      }
    }

    private def checkSize(
        bs: ByteString,
        expected: Int,
        mode: Mode
    ): SerdeResult[(Mode, ByteString, ByteString)] = {
      if (bs.length >= expected) {
        Right((mode, bs.take(expected), bs.drop(expected)))
      } else {
        Left(SerdeError.incompleteData(expected, bs.size))
      }
    }
  }

  sealed trait FixedWidth extends Mode

  case object SingleByte extends FixedWidth {
    override val prefix: Int    = 0x00 // 0b00000000
    override val negPrefix: Int = 0xc0 // 0b11000000
  }
  case object TwoByte extends FixedWidth {
    override val prefix: Int    = 0x40 // 0b01000000
    override val negPrefix: Int = 0x80 // 0b10000000
  }
  case object FourByte extends FixedWidth {
    override val prefix: Int    = 0x80 // 0b10000000
    override val negPrefix: Int = 0x40 // 0b01000000
  }
  case object MultiByte extends Mode {
    override val prefix: Int    = 0xc0 // 0b11000000
    override def negPrefix: Int = ???  // 0x00 // 0b00000000 // not needed at all
  }
}
