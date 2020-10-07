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

package org.alephium.wallet.storage

import java.io.{File, PrintWriter}
import java.nio.file.Files

import org.scalacheck.Gen

import org.alephium.crypto.wallet.{BIP32, Mnemonic}
import org.alephium.protocol.{Generators, Hash}
import org.alephium.util.{AlephiumSpec, AVector, Hex}
import org.alephium.wallet.Constants

class SecretStorageSpec() extends AlephiumSpec with Generators {

  val secretDir = Files.createTempDirectory("secret-storage-spec")
  secretDir.toFile.deleteOnExit

  val seedGen     = Gen.const(()).map(_ => Mnemonic.generate(24).get.toSeed(""))
  val passwordGen = hashGen.map(_.toHexString)

  it should "create/lock/unlock the secret storage" in {
    forAll(seedGen, passwordGen, passwordGen) {
      case (seed, password, wrongPassword) =>
        val name = Hash.generate.shortHex
        val file = new File(s"$secretDir/$name")

        val secretStorage = SecretStorage.create(seed, password, file).toOption.get
        val privateKey    = BIP32.btcMasterKey(seed).derive(Constants.path).get

        secretStorage.getCurrentPrivateKey() isE privateKey
        secretStorage.getAllPrivateKeys() isE ((privateKey, AVector(privateKey)))

        val newKey = secretStorage.deriveNextKey().toOption.get

        secretStorage.getCurrentPrivateKey() isE newKey
        secretStorage.getAllPrivateKeys() isE ((newKey, AVector(privateKey, newKey)))

        secretStorage.changeActiveKey(privateKey) isE (())

        secretStorage.getCurrentPrivateKey() isE privateKey
        secretStorage.getAllPrivateKeys() isE ((privateKey, AVector(privateKey, newKey)))

        secretStorage.changeActiveKey(privateKey.derive(0).get) is Left(SecretStorage.UnknownKey)

        secretStorage.lock()
        secretStorage.getCurrentPrivateKey() is Left(SecretStorage.Locked)

        secretStorage.unlock(wrongPassword).isLeft is true

        secretStorage.unlock(password) is Right(())
        secretStorage.getCurrentPrivateKey() isE privateKey
    }
  }

  it should "create secret storage from a file" in {

    val password = "36ae0b75ef06d2e902e473c879c6e853193760ffa5dc29dc8da76133149e0892"

    // scan pause slender around cube flavor neck shrug gadget ramp rude lend capable tone nose unhappy gift across cluster minor tragic fever detail script
    val seed = Hex.unsafe(
      "f585d130dd79d3b5bd63aa99d9bc6e6107cfbbe393b86d70e865f6e75c60a37496afc1b25cd4d1ab3b82d9b41f469c6c112a9f310e441814147ff27a5d65882b"
    )

    val rawFile =
      """{"encrypted":"8df619edbc5737594f0de56700634888ac42aa0a3d688a0721b15cd633ce5a5c2f3c1e696cf1d88a09a44899b010f717ff195ec0cd7f0b8a2a29937c162dfdb4a527bb8774aef5aaf3f0eea4d7bd17b1476d880a9e461be225412a18","salt":"a252a0caecc6673e72ee94caf5a646029b99736ff1e3f3c0f632a03556b0aa77d118b971c58903c37a74ab3504a77cee71ab66bb1c434e3c2e0d8e52e503fe80","iv":"bb341ab6dbcd3b5a5b6cee15308083ff2d12407924a399ec422494ba6b0a139d4720ab088104fdfe88d086334cd3b49e01a07a29e5bc5fc182541d9ce084d12f","version":1}"""

    val privateKey = BIP32.btcMasterKey(seed).derive(Constants.path).get

    val file      = new File(s"$secretDir/secret.json")
    val outWriter = new PrintWriter(file)
    outWriter.write(rawFile)
    outWriter.close()

    val secretStorage = SecretStorage.fromFile(file, password).toOption.get

    secretStorage.unlock(password) is Right(())

    secretStorage.getCurrentPrivateKey() isE privateKey
  }

  it should "fail to load an non existing file" in {
    val fileName        = scala.util.Random.nextString(10)
    val nonExistingFile = new File(fileName)
    SecretStorage
      .fromFile(nonExistingFile, "password")
      .swap
      .toOption
      .get is SecretStorage.SecretFileError
  }
  secretDir.toFile.listFiles.foreach(_.deleteOnExit())
}
