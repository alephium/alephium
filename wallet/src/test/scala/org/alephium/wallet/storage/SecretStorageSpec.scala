package org.alephium.wallet.storage

import java.io.{File, PrintWriter}
import java.nio.file.Files

import org.scalacheck.Gen

import org.alephium.crypto.wallet.{BIP32, Mnemonic}
import org.alephium.protocol.Generators
import org.alephium.util.{AlephiumSpec, Hex}
import org.alephium.wallet.Constants

class SecretStorageSpec() extends AlephiumSpec with Generators {

  val secretDir = Files.createTempDirectory("secret-storage-spec")
  secretDir.toFile.deleteOnExit

  val seedGen     = Gen.const(()).map(_ => Mnemonic.generate(24).get.toSeed(""))
  val passwordGen = hashGen.map(_.toHexString)

  it should "create/lock/unlock the secret storage" in {
    forAll(seedGen, passwordGen, passwordGen) {
      case (seed, password, wrongPassword) =>
        val secretStorage = SecretStorage(seed, password, secretDir)
        val privateKey    = BIP32.btcMasterKey(seed).derive(Constants.path.toSeq).get

        secretStorage.getPrivateKey() is None

        secretStorage.unlock(password) is Right(())

        secretStorage.getPrivateKey() is Option(privateKey)

        secretStorage.lock()
        secretStorage.getPrivateKey() is None

        secretStorage.unlock(wrongPassword).isLeft is true
    }
  }

  it should "create secret storage from a file" in {

    val password = "9Ep4UxpYDXRXDdGH"

    val seed = Hex.unsafe(
      "da3af395823fc57e33232d7cd14526f79f0531d46ab7d2f87f49745d503717f48380b85a83596b51542622ce300fc69006da60985bbda4186ec8c0c22bd790da")

    val rawFile =
      """
      {
        "encrypted": "6a84a9d77f4e97af228ae4e4c4419393a8e4bb36a8e1eeef5d6aedb3b0634cd499afcca1650ecb38adabef1378a76521029d808f16e77da7929895ec21eb17ef09b78370a32537aa14131c36e7af5ffe",
        "salt":"8f0e02ba4b13680740235e930989b8efc2e1dd614b92a81f472f2d9c9be775b8",
        "iv":"a694f00c92f7ef56f5664289f8f83ed06faa7c3859a21bbb45de07ff4ce3d3776d0b700eb11a45a9371f444324d73dfb140cdcf52940e92cf8614fe9e7b1a22d"
      }
      """

    val privateKey = BIP32.btcMasterKey(seed).derive(Constants.path.toSeq).get

    val file      = new File(s"$secretDir/secret.json")
    val outWriter = new PrintWriter(file)
    outWriter.write(rawFile)
    outWriter.close()

    val secretStorage = SecretStorage.fromFile(file, password).toOption.get

    secretStorage.unlock(password) is Right(())

    secretStorage.getPrivateKey() is Option(privateKey)
  }

  secretDir.toFile.listFiles.foreach(_.deleteOnExit())
}
