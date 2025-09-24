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

package org.alephium.crypto

import javax.crypto.{Cipher, SecretKeyFactory}
import javax.crypto.spec.{GCMParameterSpec, PBEKeySpec, SecretKeySpec}

import scala.util.Try

import akka.util.ByteString

import org.alephium.util.SecureAndSlowRandom

object AES {

  final case class Encrypted(encrypted: ByteString, salt: ByteString, iv: ByteString)

  private val saltByteLength       = 64
  private val ivByteLength         = 12
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
    SecureAndSlowRandom.source.nextBytes(array)
    array
  }

  private def initCipher(
      mode: Int,
      password: String,
      salt: Array[Byte],
      iv: Array[Byte]
  ): Cipher = {
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
