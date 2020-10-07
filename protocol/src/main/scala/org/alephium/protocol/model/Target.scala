package org.alephium.protocol.model

import java.math.BigInteger

import akka.util.ByteString

import org.alephium.serde._
import org.alephium.util.{Bytes, Numeric}

/*
 * value = mantissa * 256 ^ (exponent - 3)
 * value should not be negative always
 */
final case class Target(val bits: ByteString) {
  lazy val value: BigInteger = Target.fromCompactBitsUnsafe(bits)
}

object Target {
  implicit val serde: Serde[Target] = Serde.bytesSerde(4).xmap(unsafe, _.bits)

  private val max: BigInteger = BigInteger.ONE.shiftLeft(256)

  val Max: Target = unsafe(max.subtract(BigInteger.ONE))

  def unsafe(byteString: ByteString): Target = {
    require(byteString.length == 4)
    new Target(byteString)
  }

  def unsafe(value: BigInteger): Target = {
    require(Numeric.nonNegative(value) && value.compareTo(max) < 0)
    new Target(toCompactBitsUnsafe(value))
  }

  @SuppressWarnings(Array("org.wartremover.warts.TraversableOps"))
  def fromCompactBitsUnsafe(bits: ByteString): BigInteger = {
    assume(bits.length == 4)
    val size: Int            = Bytes.toPosInt(bits(0))
    val mantissa: BigInteger = new BigInteger(1, bits.tail.toArray)
    if (size >= 3) {
      mantissa.shiftLeft(8 * (size - 3))
    } else {
      mantissa.shiftRight(8 * (3 - size))
    }
  }

  @SuppressWarnings(Array("org.wartremover.warts.TraversableOps"))
  def toCompactBitsUnsafe(value: BigInteger): ByteString = {
    val size: Int = (value.bitLength() + 7) / 8
    val mantissa = if (size <= 3) {
      Bytes.from(value.intValue() << (8 * (3 - size)))
    } else {
      Bytes.from(value.shiftRight(8 * (size - 3)).intValue())
    }
    ByteString(size.toByte) ++ mantissa.tail
  }
}
