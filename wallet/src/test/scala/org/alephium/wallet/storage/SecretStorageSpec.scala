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

import org.alephium.crypto.wallet.{BIP32, Mnemonic}
import org.alephium.protocol.{Generators, Hash}
import org.alephium.util.{AlephiumSpec, AVector, Hex}
import org.alephium.wallet.Constants

class SecretStorageSpec() extends AlephiumSpec with Generators {

  val secretDir = Files.createTempDirectory("secret-storage-spec")
  secretDir.toFile.deleteOnExit

  val mnemonicGen = Mnemonic.generate(24).get
  val passwordGen = hashGen.map(_.toHexString)
  val path        = Constants.path

  it should "create/lock/unlock/delete the secret storage" in {
    forAll(mnemonicGen, passwordGen, passwordGen) { case (mnemonic, password, wrongPassword) =>
      val seed  = mnemonic.toSeed(None)
      val name  = Hash.generate.shortHex
      val file  = new File(s"$secretDir/$name")
      val miner = false

      val secretStorage =
        SecretStorage.create(mnemonic, None, password, miner, file, path).toOption.get
      val privateKey = BIP32.btcMasterKey(seed).derive(path).get

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

      secretStorage.unlock(wrongPassword, None).isLeft is true

      secretStorage.unlock(password, None) is Right(())
      secretStorage.getCurrentPrivateKey() isE privateKey

      secretStorage.revealMnemonic(password) is Right(mnemonic)
      secretStorage.revealMnemonic(wrongPassword).isLeft is true

      secretStorage.delete(password) is Right(())
      secretStorage.delete(password) is Left(SecretStorage.SecretFileError)
      secretStorage.unlock(password, None) is Left(SecretStorage.SecretFileError)

    }
  }

  it should "handle mnemonic passphrase" in {
    forAll(mnemonicGen, passwordGen, passwordGen) { case (mnemonic, password, passphrase) =>
      val seed  = mnemonic.toSeed(Some(passphrase))
      val name  = Hash.generate.shortHex
      val file  = new File(s"$secretDir/$name")
      val miner = false

      val secretStorage =
        SecretStorage.create(mnemonic, Some(passphrase), password, miner, file, path).toOption.get

      val privateKey = BIP32.btcMasterKey(seed).derive(path).get

      secretStorage.getCurrentPrivateKey() isE privateKey

      secretStorage.lock()

      //unlocking without passphrase gives another seed
      secretStorage.unlock(password, None)
      secretStorage.getCurrentPrivateKey() isnotE privateKey

      secretStorage.lock()

      secretStorage.unlock(password, Some(passphrase))
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
      """{"encrypted":"267cdaf3f8a740c6fb70ec0413d90dd7389c0898a09cc08b476f66c232572ff2590a40bd1d6e560596880de981939fb6a7a7300a390bcf6c762dae0a36d8188c6cc1b2214c6e459aa2cbf2df1c6cdf16703748ae3e386879e6fd03927c24b85a633e79f71abf432fea719a622ed68b6beda79b509f8b3e1c3a192466f6cd21a9ce09f935e27f32c6083dd1e8cb80fdd8fc67b2a70f22df0468a735d42e5c83d5dc95f8a0a9ac228a57b8","salt":"c2eeb77b1d46fa53db20130cd9c0a6f281459274d06e3e597c83d0db4e3d1dd503eb86b6b2e1cb18b5563b02150ee02e11c262b024e6e7787f8de48cabb2d56b","iv":"d6a57f8ecf1df03b1dd1095a66608c3a580feb539a0dc8094f2c3de07a516cc1aacf0acd00de5a79dd6f4b3802640da78088f52e810b40739e04e8c30d0104f8","version":1}"""

    val privateKey = BIP32.btcMasterKey(seed).derive(path).get

    val file      = new File(s"$secretDir/secret.json")
    val outWriter = new PrintWriter(file)
    outWriter.write(rawFile)
    outWriter.close()

    val secretStorage = SecretStorage.fromFile(file, password, path, None).toOption.get

    secretStorage.unlock(password, None) is Right(())

    secretStorage.getCurrentPrivateKey() isE privateKey
  }

  it should "fail to create twice the same file" in {
    val file = new File(s"$secretDir/secret.json")

    forAll(mnemonicGen) { mnemonic =>
      SecretStorage
        .create(mnemonic, None, "password", false, file, path)
        .leftValue is SecretStorage.SecretFileAlreadyExists
    }
  }

  it should "load an existing file" in {
    val file = new File(s"$secretDir/secret.json")

    val secretStorage = SecretStorage.load(file, path).rightValue

    secretStorage.getCurrentPrivateKey() is Left(SecretStorage.Locked)
  }

  it should "fail to load an non existing file" in {
    val fileName        = scala.util.Random.nextString(10)
    val nonExistingFile = new File(fileName)
    SecretStorage
      .fromFile(nonExistingFile, "password", path, None)
      .swap
      .toOption
      .get is SecretStorage.SecretFileError
  }

  it should "fail to load a file with invalid state" in {
    val rawFile = """invalid-state"""

    val file      = new File(s"$secretDir/invalid-state.json")
    val outWriter = new PrintWriter(file)
    outWriter.write(rawFile)
    outWriter.close()

    SecretStorage.load(file, path).leftValue is SecretStorage.CannotParseFile
  }

  secretDir.toFile.listFiles.foreach(_.deleteOnExit())
}
