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
  AlephiumSpec.addCleanTask(() => AlephiumSpec.delete(secretDir))

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
        SecretStorage.create(mnemonic, None, password, miner, file, path).rightValue
      val privateKey = BIP32.btcMasterKey(seed).derive(path).get

      secretStorage.getActivePrivateKey() isE privateKey
      secretStorage.getAllPrivateKeys() isE ((privateKey, AVector(privateKey)))

      val newKey = secretStorage.deriveNextKey().rightValue

      secretStorage.getActivePrivateKey() isE newKey
      secretStorage.getAllPrivateKeys() isE ((newKey, AVector(privateKey, newKey)))

      secretStorage.changeActiveKey(privateKey) isE (())

      secretStorage.getActivePrivateKey() isE privateKey
      secretStorage.getAllPrivateKeys() isE ((privateKey, AVector(privateKey, newKey)))

      secretStorage.changeActiveKey(privateKey.derive(0).get) is Left(SecretStorage.UnknownKey)

      secretStorage.lock()
      secretStorage.getActivePrivateKey() is Left(SecretStorage.Locked)

      secretStorage.unlock(wrongPassword, None).isLeft is true

      secretStorage.unlock(password, None) is Right(())
      secretStorage.getActivePrivateKey() isE privateKey

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
        SecretStorage.create(mnemonic, Some(passphrase), password, miner, file, path).rightValue

      val privateKey = BIP32.btcMasterKey(seed).derive(path).get

      secretStorage.getActivePrivateKey() isE privateKey

      secretStorage.lock()
      secretStorage.unlock(password, None).leftValue is SecretStorage.InvalidMnemonicPassphrase
      secretStorage.unlock(password, Some(passphrase))
      secretStorage.getActivePrivateKey() isE privateKey
    }
  }

  it should "create secret storage from a file" in {

    val password   = "36ae0b75ef06d2e902e473c879c6e853193760ffa5dc29dc8da76133149e0892"
    val mnemonicph = "5bd63aa99d9bc6e610e473c879c6e853193760ffa5dc29dc8da76133149e0892"

    // scan pause slender around cube flavor neck shrug gadget ramp rude lend capable tone nose unhappy gift across cluster minor tragic fever detail script
    val seed = Hex.unsafe(
      "7c3308b5b2fe6c6feb2ed8ca3e54fc91b8a411c09e8c62ab2b19e3dd24129eacc9f44f64e9ae2101cdbe9ea1f36e1715bba38ced09ae8778eb05cf11305349cd"
    )

    val rawFile =
      """{"encrypted":"46e53f15468c210a31a738b2875b0202301d8ac470151090a18b1a0f36bcaef9f091dc1423b07794b1c9702de6ca4e00111c7b4daeedfa0bffdae417b102224f626f4cf143d061b5e3848afeb74ab6e0624af37fd9beaf76ce8fbb6d9176d1e739c34fff30ac7dcd11071487420c0c14d8ba81245d4007dfe635346b659913052f819c5c6766a45df15c2d47a22550da39632b27256a0a475fc328d9b5e3ccc937dcab1c8e1950bdd9aaa9ad97f0cb3a32d4b1a9a5449558d36549f371f850fb11d7cd577808c72744b7a1","salt":"18f4b8cfc2d99942a015046936dcae94afcc3fad64d4c2a8e6d7ee91a8c198f83d8f02fbfc5b1dadfedb7b2bc8f539b3832c6ee4188ccb51b06b8d266c6b81f0","iv":"695a244a83d99b34434ff255d3ca8c22e784c57958475935fe8fbb91e27aeccbdc2e558ba04b4482c73405b88e3afb3f9bd45c61731e0b15a040f170b607709b","version":1}"""

    val privateKey = BIP32.btcMasterKey(seed).derive(path).get

    val file      = new File(s"$secretDir/secret.json")
    val outWriter = new PrintWriter(file)
    outWriter.write(rawFile)
    outWriter.close()

    SecretStorage
      .fromFile(file, password, path, None)
      .leftValue is SecretStorage.InvalidMnemonicPassphrase

    val secretStorage =
      SecretStorage.fromFile(file, password, path, Some(mnemonicph)).rightValue

    secretStorage.unlock(password, None).leftValue is SecretStorage.InvalidMnemonicPassphrase
    secretStorage.unlock(password, Some(mnemonicph)) is Right(())

    secretStorage.getActivePrivateKey() isE privateKey
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

    secretStorage.getActivePrivateKey() is Left(SecretStorage.Locked)
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
