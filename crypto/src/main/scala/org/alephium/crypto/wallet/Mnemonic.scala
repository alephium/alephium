package org.alephium.crypto.wallet

import java.nio.charset.StandardCharsets
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

import scala.io.Source

import akka.util.ByteString

import org.alephium.crypto.Sha256
import org.alephium.util.{AVector, Bits, Random}

//scalastyle:off magic.number

final case class Mnemonic private (words: AVector[String]) extends AnyVal {
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
  final case class Size private (value: Int) extends AnyVal
  object Size {
    val list: AVector[Size] =
      AVector(new Size(12), new Size(15), new Size(18), new Size(21), new Size(24))

    def apply(size: Int): Option[Size] =
      Option.when(validate(size))(new Size(size))

    def validate(size: Int): Boolean = list.contains(new Size(size))
  }
  val entropySizes: Seq[Int] = Seq(16, 20, 24, 28, 32)

  val pbkdf2Algorithm: String = "PBKDF2WithHmacSHA512"
  val pbkdf2Iterations: Int   = 2048
  val pbkdf2KeyLength: Int    = 512

  lazy val englishWordlist: AVector[String] = {
    val stream = Mnemonic.getClass.getResourceAsStream("/bip39_english_wordlist.txt")
    AVector.from(Source.fromInputStream(stream, "UTF-8").getLines().to(Iterable))
  }

  def generate(size: Int): Option[Mnemonic] =
    Size(size).map(generate)

  def generate(size: Size): Mnemonic = {
    val typeIndex   = Size.list.indexWhere(_ == size)
    val entropySize = entropySizes(typeIndex)
    val rawEntropy  = Array.ofDim[Byte](entropySize)
    Random.source.nextBytes(rawEntropy)
    val entropy = ByteString.fromArrayUnsafe(rawEntropy)
    fromEntropyUnsafe(entropy)
  }

  protected[wallet] def validateWords(words: AVector[String]): Boolean = {
    Size.validate(words.length) && words.forall(englishWordlist.contains)
  }

  def fromWords(words: AVector[String]): Option[Mnemonic] = {
    Option.when(validateWords(words))(new Mnemonic(words))
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
    new Mnemonic(worldIndexes.map(englishWordlist.apply))
  }

  def fromEntropyUnsafe(entropy: ByteString): Mnemonic = {
    assume(validateEntropy(entropy))
    unsafe(entropy)
  }

  def from(entropy: ByteString): Option[Mnemonic] = {
    Option.when(validateEntropy(entropy))(unsafe(entropy))
  }
}
