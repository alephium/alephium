package org.alephium.crypto.wallet

import java.math.BigInteger
import java.nio.charset.StandardCharsets

import scala.annotation.tailrec

import akka.util.ByteString
import org.bouncycastle.crypto.digests.SHA512Digest
import org.bouncycastle.crypto.macs.HMac
import org.bouncycastle.crypto.params.KeyParameter

import org.alephium.crypto.{SecP256K1, SecP256K1PrivateKey, SecP256K1PublicKey}
import org.alephium.util.Bytes

//scalastyle:off magic.number
object BIP32 {
  def masterKey(prefix: String, seed: ByteString): ExtendedPrivateKey = {
    val i        = hmacSha512(ByteString.fromArrayUnsafe(prefix.getBytes(StandardCharsets.UTF_8)), seed)
    val (il, ir) = i.splitAt(32)
    ExtendedPrivateKey(SecP256K1PrivateKey.unsafe(il), ir, Seq.empty)
  }

  def btcMasterKey(seed: ByteString): ExtendedPrivateKey = masterKey("Bitcoin seed", seed)

  def alfMasterKey(seed: ByteString): ExtendedPrivateKey = masterKey("Alephium seed", seed)

  def isHardened(index: Int): Boolean = index < 0

  def harden(index: Int): Int = index | 0x80000000

  def hmacSha512(key: ByteString, data: ByteString): ByteString = {
    val mac = new HMac(new SHA512Digest())
    mac.init(new KeyParameter(key.toArray))
    mac.update(data.toArray, 0, data.length.toInt)
    val out = new Array[Byte](64)
    mac.doFinal(out, 0)
    ByteString.fromArrayUnsafe(out)
  }

  final case class ExtendedPrivateKey protected[wallet] (privateKey: SecP256K1PrivateKey,
                                                         chainCode: ByteString,
                                                         path: Seq[Int]) {
    def publicKey: SecP256K1PublicKey = privateKey.publicKey

    def extendedPublicKey: ExtendedPublicKey =
      ExtendedPublicKey(privateKey.publicKey, chainCode, path)

    def derive(index: Int): Option[ExtendedPrivateKey] = {
      val i = {
        if (isHardened(index)) ByteString(0) ++ privateKey.bytes ++ Bytes.from(index)
        else privateKey.publicKey.bytes ++ Bytes.from(index)
      }
      val (il, ir) = hmacSha512(chainCode, i).splitAt(32)
      val p        = new BigInteger(1, il.toArray)
      if (p.compareTo(SecP256K1.params.getN) >= 0) None
      else {
        val newPrivateKey = SecP256K1PrivateKey.unsafe(il).add(privateKey)
        if (newPrivateKey.isZero) None
        else {
          Some(ExtendedPrivateKey(newPrivateKey, ir, path :+ index))
        }
      }
    }

    def derive(path: Seq[Int]): Option[ExtendedPrivateKey] = {
      @tailrec
      def iter(acc: ExtendedPrivateKey, i: Int): Option[ExtendedPrivateKey] = {
        if (i == path.length) Some(acc)
        else {
          acc.derive(path(i)) match {
            case Some(newAcc) => iter(newAcc, i + 1)
            case None         => None
          }
        }
      }
      iter(this, 0)
    }
  }

  final case class ExtendedPublicKey protected[wallet] (publicKey: SecP256K1PublicKey,
                                                        chainCode: ByteString,
                                                        path: Seq[Int]) {
    def derive(index: Int): Option[ExtendedPublicKey] = {
      assume(!isHardened(index))
      val i        = publicKey.bytes ++ Bytes.from(index)
      val (il, ir) = hmacSha512(chainCode, i).splitAt(32)
      val p        = new BigInteger(1, il.toArray)
      if (p.compareTo(SecP256K1.params.getN) >= 0) None
      else {
        val ki = SecP256K1PrivateKey.unsafe(il).publicKey.unsafePoint.add(publicKey.unsafePoint) // safe by construction
        if (ki.isInfinity) None
        else {
          val newPublicKey =
            SecP256K1PublicKey.unsafe(ByteString.fromArrayUnsafe(ki.getEncoded(true)))
          Some(ExtendedPublicKey(newPublicKey, ir, path :+ index))
        }
      }
    }

    def derive(path: Seq[Int]): Option[ExtendedPublicKey] = {
      assume(path.forall(!isHardened(_)))
      @tailrec
      def iter(acc: ExtendedPublicKey, i: Int): Option[ExtendedPublicKey] = {
        if (i == path.length) Some(acc)
        else {
          acc.derive(path(i)) match {
            case Some(newAcc) => iter(newAcc, i + 1)
            case None         => None
          }
        }
      }
      iter(this, 0)
    }
  }
}
