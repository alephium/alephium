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
import org.alephium.protocol.model.NetworkType
import org.alephium.util.{AlephiumSpec, AVector, Hex}
import org.alephium.wallet.Constants

class SecretStorageSpec() extends AlephiumSpec with Generators {

  val secretDir = Files.createTempDirectory("secret-storage-spec")
  secretDir.toFile.deleteOnExit

  val seedGen     = Gen.const(()).map(_ => Mnemonic.generate(24).get.toSeed(""))
  val passwordGen = hashGen.map(_.toHexString)
  val path        = Constants.path(NetworkType.Devnet)

  it should "create/lock/unlock the secret storage" in {
    forAll(seedGen, passwordGen, passwordGen) {
      case (seed, password, wrongPassword) =>
        val name  = Hash.generate.shortHex
        val file  = new File(s"$secretDir/$name")
        val miner = false

        val secretStorage = SecretStorage.create(seed, password, miner, file, path).toOption.get
        val privateKey    = BIP32.btcMasterKey(seed).derive(path).get

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
      """{"encrypted":"736d41462857238addf48f279b6ea5775a7663bfe84a48db562dddd5de148bd6abfa47c7ec0604642d6ef8e3b26d532086f8c205bb5831ea2438370aee035ebe254edaef3304f5046e8d017a8ddc0e7fc8dd5d9db802a2c8bcabe71068","salt":"6a876935df614d6da10cd892a7236c2c3222e06d63d94b7ff35b046d83569c5daae62fd5cb8ab2b912a36ed4c4de5edbeb63096e00fd4c4b04d816e6d4342ff2","iv":"3e5f9b33f594235b40da29cf44a3addee221ac49abed5f1f288b820320c636c8c6ed6c6e02e7f3e201097eace60fcaa91f098401b755b047cec62cd4c660cc97","version":1}"""

    val privateKey = BIP32.btcMasterKey(seed).derive(path).get

    val file      = new File(s"$secretDir/secret.json")
    val outWriter = new PrintWriter(file)
    outWriter.write(rawFile)
    outWriter.close()

    val secretStorage = SecretStorage.fromFile(file, password, path).toOption.get

    secretStorage.unlock(password) is Right(())

    secretStorage.getCurrentPrivateKey() isE privateKey
  }

  it should "fail to load an non existing file" in {
    val fileName        = scala.util.Random.nextString(10)
    val nonExistingFile = new File(fileName)
    SecretStorage
      .fromFile(nonExistingFile, "password", path)
      .swap
      .toOption
      .get is SecretStorage.SecretFileError
  }
  secretDir.toFile.listFiles.foreach(_.deleteOnExit())
}
