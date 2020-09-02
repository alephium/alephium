package org.alephium.crypto

import javax.crypto.{Cipher, SecretKeyFactory}
import javax.crypto.spec.{GCMParameterSpec, PBEKeySpec, SecretKeySpec}

import scala.util.Try

import akka.util.ByteString

import org.alephium.util.Random

object AES {

  final case class Encrypted(encrypted: ByteString, salt: ByteString, iv: ByteString)

  private val saltByteLength       = 64
  private val ivByteLength         = 64
  private val authTagLength        = 128
  private val keyAlgorithm         = "PBKDF2WithHmacSHA256"
  private val iterationCount       = 10000
  private val keyLength            = 256
  private val cipherAlgorithm      = "AES"
  private val cipherTransformation = s"$cipherAlgorithm/GCM/NoPadding"

  def encrypt(data: ByteString, password: String): Encrypted = {
    val salt = randomBytesOf(saltByteLength)
    val iv   = randomBytesOf(ivByteLength)

    val cipher = initCipher(Cipher.ENCRYPT_MODE, password, salt, iv)

    val encrypted = cipher.doFinal(data.toArray)

    Encrypted(byteString(encrypted), byteString(salt), byteString(iv))
  }

  def decrypt(encrypted: Encrypted, password: String): Try[ByteString] = {
    val cipher =
      initCipher(Cipher.DECRYPT_MODE, password, encrypted.salt.toArray, encrypted.iv.toArray)

    Try(byteString(cipher.doFinal(encrypted.encrypted.toArray)))
  }

  private def randomBytesOf(length: Int): Array[Byte] = {
    val array = Array.ofDim[Byte](length)
    Random.source.nextBytes(array)
    array
  }

  private def initCipher(mode: Int,
                         password: String,
                         salt: Array[Byte],
                         iv: Array[Byte]): Cipher = {
    val keySpec          = new PBEKeySpec(password.toCharArray, salt, iterationCount, keyLength)
    val secretKeyFactory = SecretKeyFactory.getInstance(keyAlgorithm)
    val key              = secretKeyFactory.generateSecret(keySpec).getEncoded
    val derivedKey       = new SecretKeySpec(key, cipherAlgorithm)
    val parameters       = new GCMParameterSpec(authTagLength, iv)
    val cipher           = Cipher.getInstance(cipherTransformation)

    cipher.init(mode, derivedKey, parameters)
    cipher
  }

  private def byteString(array: Array[Byte]): ByteString = ByteString.fromArrayUnsafe(array)
}
