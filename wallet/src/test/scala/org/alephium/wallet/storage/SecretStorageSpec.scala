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
import org.alephium.protocol.model.NetworkId
import org.alephium.util.{AlephiumSpec, AVector, Hex}
import org.alephium.wallet.Constants

class SecretStorageSpec() extends AlephiumSpec with Generators {

  val secretDir = Files.createTempDirectory("secret-storage-spec")
  secretDir.toFile.deleteOnExit

  val mnemonicGen = Mnemonic.generate(24).get
  val passwordGen = hashGen.map(_.toHexString)
  val path        = Constants.path(NetworkId.AlephiumMainNet)

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
      """{"encrypted":"47216dec548143e584a97c32fc0d2019b28dd39e44221ed7306ce1d6072e7b5e9e334d4ae389848619b345ded3eddbeb9b863f60aa4968d41baf556d9937cbb70d88740feb04c8c30b756201b2a14a9c9cbe77fd20af9d91ad1346995fbab28312ee2857749d27453938031a04ef3773756239fedefcedec549b1b095a7710a639745440406592acfd22224cc7a2db81600acbc6a0f244ff0dcf863f79a58f22f3df303713741fb8209c","salt":"ab9426ea499680ebd7a84eaade6bdfe5ecdad8b25d0dd92277bae845fd11c1ad58766311b02ca41fa509d06ef5afa2e8bf817b6febcb78fc9d831b71a1de8138","iv":"1eb45e5397e7f7edfa5f699c8c4966c7ca07a32f2c77bd730fa71e5746fdf09895b309aba2da5e0a24a00529ccb4c18839dd4cb5f34083672b1f3364f86524c1","version":1}"""

    val privateKey = BIP32.btcMasterKey(seed).derive(path).get

    val file      = new File(s"$secretDir/secret.json")
    val outWriter = new PrintWriter(file)
    outWriter.write(rawFile)
    outWriter.close()

    val secretStorage = SecretStorage.fromFile(file, password, path, None).toOption.get

    secretStorage.unlock(password, None) is Right(())

    secretStorage.getCurrentPrivateKey() isE privateKey
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
  secretDir.toFile.listFiles.foreach(_.deleteOnExit())
}
