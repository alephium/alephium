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
        val secretStorage = SecretStorage(seed, password, secretDir).toOption.get
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

    val password = "36ae0b75ef06d2e902e473c879c6e853193760ffa5dc29dc8da76133149e0892"

    // scan pause slender around cube flavor neck shrug gadget ramp rude lend capable tone nose unhappy gift across cluster minor tragic fever detail script
    val seed = Hex.unsafe(
      "f585d130dd79d3b5bd63aa99d9bc6e6107cfbbe393b86d70e865f6e75c60a37496afc1b25cd4d1ab3b82d9b41f469c6c112a9f310e441814147ff27a5d65882b"
    )

    val rawFile =
      """
      {
        "encrypted":"27adda4459431e84d208b4f7ad9d347facc3f9483cef87a867976dd9952262af3bd6d1562e879b31fceb814212fd3fb1aa778c03ee487ced705fa8c6005a86cf57f887994db994ad2957b4955a1e092c",
        "salt":"a65767906fed48ccb2c40f08f4c344d5bb7a912bf2eb5a726b7276b224cc50e88cdc9c0ef212d1fd5c079a57a7ff12c8a7edbdad8ccf80d1c5e32fcc32e251c9",
        "iv":"bddac30c9be7af09a0ede16a5f4ca2c439491781275901fc2ad1e2d465c203bc635b83314b396a4b7d6385539aa10cbd6d8579c0d22a7307fa7867a41eb51adc"
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

  it should "fail to load an non existing file" in {
    val fileName        = scala.util.Random.nextString(10)
    val nonExistingFile = new File(fileName)
    SecretStorage
      .fromFile(nonExistingFile, "password")
      .swap
      .toOption
      .get is s"$fileName (No such file or directory)"
  }
  secretDir.toFile.listFiles.foreach(_.deleteOnExit())
}
