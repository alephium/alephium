package org.alephium.wallet.storage

import java.nio.file.Files

import org.scalacheck.Gen

import org.alephium.crypto.wallet.{BIP32, Mnemonic}
import org.alephium.protocol.Generators
import org.alephium.util.AlephiumSpec
import org.alephium.wallet.Constants

class SecretStorageSpec() extends AlephiumSpec with Generators {

  val secretDir = Files.createTempDirectory("secret-storage-spec")

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
}
