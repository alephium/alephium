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

import akka.util.ByteString

import org.alephium.serde.RandomBytes
import org.alephium.util.AVector

private[crypto] trait PrivateKey extends RandomBytes

private[crypto] trait PublicKey extends RandomBytes

private[crypto] trait Signature extends RandomBytes

private[crypto] trait SignatureSchema[D <: PrivateKey, Q <: PublicKey, S <: Signature] {

  def generatePriPub(): (D, Q)

  def secureGeneratePriPub(): (D, Q)

  def sign(message: ByteString, privateKey: D): S = {
    sign(message.toArray, privateKey.bytes.toArray)
  }

  def sign(bytes: RandomBytes, privateKey: D): S = {
    sign(bytes.bytes.toArray, privateKey.bytes.toArray)
  }

  def sign(message: AVector[Byte], privateKey: D): S = {
    sign(message.toArray, privateKey.bytes.toArray)
  }

  protected def sign(message: Array[Byte], privateKey: Array[Byte]): S

  def verify(message: ByteString, signature: S, publicKey: Q): Boolean = {
    verify(message.toArray, signature.bytes.toArray, publicKey.bytes.toArray)
  }

  def verify(message: AVector[Byte], signature: S, publicKey: Q): Boolean = {
    verify(message.toArray, signature.bytes.toArray, publicKey.bytes.toArray)
  }

  protected def verify(
      message: Array[Byte],
      signature: Array[Byte],
      publicKey: Array[Byte]
  ): Boolean
}
