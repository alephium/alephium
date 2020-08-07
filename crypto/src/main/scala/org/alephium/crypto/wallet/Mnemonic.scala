package org.alephium.crypto.wallet

import java.nio.charset.StandardCharsets
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

import scala.io.Source

import akka.util.ByteString

import org.alephium.crypto.Sha256
import org.alephium.util.{AVector, Bits, Random}

//scalastyle:off magic.number

final case class Mnemonic(val words: Seq[String]) extends AnyVal {
  def toSeed(passphrase: String): ByteString = {
    val mnemonic     = words.mkString(" ").toCharArray
    val extendedPass = s"mnemonic${passphrase}".getBytes(StandardCharsets.UTF_8)
    val spec = new PBEKeySpec(
      mnemonic,
      extendedPass,
      Mnemonic.pbkdf2Iterations,
      Mnemonic.pbkdf2KeyLength
    )
    val factory = SecretKeyFactory.getInstance(Mnemonic.pbkdf2Algorithm)
    ByteString.fromArrayUnsafe(factory.generateSecret(spec).getEncoded)
  }
}

object Mnemonic {
  val worldListSizes: Seq[Int] = Seq(12, 15, 18, 21, 24)
  val entropySizes: Seq[Int]   = Seq(16, 20, 24, 28, 32)

  val pbkdf2Algorithm: String = "PBKDF2WithHmacSHA512"
  val pbkdf2Iterations: Int   = 2048
  val pbkdf2KeyLength: Int    = 512

  lazy val englishWordlist: Seq[String] = {
    val stream = Mnemonic.getClass.getResourceAsStream("/bip39_english_wordlist.txt")
    Source.fromInputStream(stream, "UTF-8").getLines().toSeq
  }

  def generate(size: Int): Mnemonic = {
    assume(worldListSizes.contains(size))
    val typeIndex   = worldListSizes.indexOf(size)
    val entropySize = entropySizes(typeIndex)
    val rawEntropy  = Array.ofDim[Byte](entropySize)
    Random.source.nextBytes(rawEntropy)
    val entropy = ByteString.fromArrayUnsafe(rawEntropy)
    fromEntropyUnsafe(entropy)
  }

  protected[wallet] def validateWords(words: Seq[String]): Boolean = {
    worldListSizes.contains(words.length) && words.forall(englishWordlist.contains)
  }

  def fromWordsUnsafe(words: Seq[String]): Mnemonic = {
    assume(validateWords(words))
    new Mnemonic(words)
  }

  def fromWords(words: Seq[String]): Option[Mnemonic] = {
    if (validateWords(words)) Some(new Mnemonic(words)) else None
  }

  protected[wallet] def validateEntropy(entropy: ByteString): Boolean = {
    entropySizes.contains(entropy.length)
  }

  protected[wallet] def unsafe(entropy: ByteString): Mnemonic = {
    val checkSum = Sha256.hash(entropy).bytes.take(1)
    val extendedEntropy = AVector
      .from(entropy ++ checkSum)
      .flatMap(Bits.from)
      .take(entropy.length * 8 + entropy.length / 4)
    val worldIndexes = extendedEntropy.grouped(11).map(Bits.toInt)
    new Mnemonic(worldIndexes.map(englishWordlist.apply).toSeq)
  }

  def fromEntropyUnsafe(entropy: ByteString): Mnemonic = {
    assume(validateEntropy(entropy))
    unsafe(entropy)
  }

  def from(entropy: ByteString): Option[Mnemonic] = {
    if (validateEntropy(entropy)) Some(unsafe(entropy)) else None
  }
}
