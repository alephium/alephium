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

        secretStorage.getCurrentPrivateKey() is Left(SecretStorage.Locked)

        secretStorage.unlock(password) is Right(())

        secretStorage.getCurrentPrivateKey() isE privateKey

        secretStorage.lock()
        secretStorage.getCurrentPrivateKey() is Left(SecretStorage.Locked)

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
        "encrypted":"406f80e352e5830468fb4fb27c9c334b0e258496b43daae48761d335f6d75fc654b5af0c66bdaa853576ab07da3284308251619743b2dfc918f993c6141a6d652630217651d08d0324aecfe27f1d17324f6f9d1e0f9ff502",
        "salt":"64c3552142ef024ed543a87dbc898f1f71299b8650fdadf24d101f36d6608d81500799e96c00ed04baf077a4b7ac33793e9a75ba3880f499d3507de2832d177f",
        "iv":"d468cdbc9a706d0d53fd061595ccb1c3b2424c239e7f62d56de9d3fca6a2b4d4ab0604789f55771f7ff3eb1398f3fbb85da762b9b77a10ea80ced885c731f007"
      }

      """

    val privateKey = BIP32.btcMasterKey(seed).derive(Constants.path.toSeq).get

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
