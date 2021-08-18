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
import org.alephium.protocol.model.ChainId
import org.alephium.util.{AlephiumSpec, AVector, Hex}
import org.alephium.wallet.Constants

class SecretStorageSpec() extends AlephiumSpec with Generators {

  val secretDir = Files.createTempDirectory("secret-storage-spec")
  secretDir.toFile.deleteOnExit

  val mnemonicGen = Mnemonic.generate(24).get
  val passwordGen = hashGen.map(_.toHexString)
  val path        = Constants.path(ChainId.AlephiumMainNet)

  it should "create/lock/unlock/delete the secret storage" in {
    forAll(mnemonicGen, passwordGen, passwordGen) { case (mnemonic, password, wrongPassword) =>
      val seed  = mnemonic.toSeed("")
      val name  = Hash.generate.shortHex
      val file  = new File(s"$secretDir/$name")
      val miner = false

      val secretStorage =
        SecretStorage.create(seed, password, miner, file, path, mnemonic).toOption.get
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

      secretStorage.unlock(wrongPassword).isLeft is true

      secretStorage.unlock(password) is Right(())
      secretStorage.getCurrentPrivateKey() isE privateKey

      secretStorage.getMnemonic(password) is Right(mnemonic)
      secretStorage.getMnemonic(wrongPassword).isLeft is true

      secretStorage.delete(password) is Right(())
      secretStorage.unlock(password) is Left(SecretStorage.SecretFileError)
    }
  }

  it should "create secret storage from a file" in {

    val password = "36ae0b75ef06d2e902e473c879c6e853193760ffa5dc29dc8da76133149e0892"

    // scan pause slender around cube flavor neck shrug gadget ramp rude lend capable tone nose unhappy gift across cluster minor tragic fever detail script
    val seed = Hex.unsafe(
      "f585d130dd79d3b5bd63aa99d9bc6e6107cfbbe393b86d70e865f6e75c60a37496afc1b25cd4d1ab3b82d9b41f469c6c112a9f310e441814147ff27a5d65882b"
    )

    val rawFile =
      """{"encrypted":"6fe09b873d5a9ae3afba629a9f905e914c5daf4a20b7f67c7553f9bfad68e548053002c472a0243ce2cdb3bb233e17a8a7fb1707a61e9f90a35cd347877bbf4e82ed06fd74161bfe536151b6f32e7d10f232d669304859daf84bef3c696f383fa58017cb469750e0bb017a95a1ef64331e0a6ce7bd06c53209ddcaf9172faa753f3a1240c6bc2b926859b0feede02ccbc27de9dfa0ce6112c87e9d26df3834766f9ed316c37a7a9285e29631d5451afdc5be8d00c0b276b872aaed7f747e83905c340ce635c070c41cb1fa9b0c25765eb909b0ab5c89b226617ad6949ef23b556f6343f61a1c32e9e49104e8","salt":"d1a5c20b8fc9b66fc56065be0f0780fdf1a5e55e07fdfeaa7214b681209ad5b8c4e4e4d0deb0e168d62616638730928b1663980206416f766a49da05eda14f1e","iv":"db6f4123a633fb6d98a49445870ea59db50bc0132ca362c5f00b4db63a4b4f4d873347e2143dbbbe82e904cb819f52414d76b2f58d91ab75628db44cd2e594d0","version":1}"""

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
