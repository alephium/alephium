package org.alephium.wallet.service

import java.nio.file.Path

import scala.concurrent.{ExecutionContext, Future}

import org.alephium.crypto.ALFSignatureSchema
import org.alephium.crypto.wallet.BIP32.ExtendedPrivateKey
import org.alephium.crypto.wallet.Mnemonic
import org.alephium.protocol.vm.LockupScript
import org.alephium.util.Hex
import org.alephium.wallet.storage.SecretStorage
import org.alephium.wallet.web.BlockFlowClient

trait WalletService {
  def createWallet(password: String,
                   mnemonicSize: Int,
                   mnemonicPassphrase: Option[String]): Future[Either[String, Mnemonic]]

  def restoreWallet(password: String,
                    mnemonic: String,
                    mnemonicPassphrase: Option[String]): Future[Either[String, Unit]]

  def lockWallet(): Future[Either[String, Unit]]
  def unlockWallet(password: String): Future[Either[String, Unit]]
  def getBalance(): Future[Either[String, Long]]
  def getAddress(): Future[Either[String, String]]
  def transfer(address: String, amount: Long): Future[Either[String, String]]
}

object WalletService {

  def apply(blockFlowClient: BlockFlowClient, secretDir: Path)(
      implicit executionContext: ExecutionContext): WalletService =
    new Impl(blockFlowClient, secretDir)

  private var maybeSecretStorage: Option[SecretStorage] = None

  private class Impl(blockFlowClient: BlockFlowClient, secretDir: Path)(
      implicit executionContext: ExecutionContext)
      extends WalletService {
    def createWallet(password: String,
                     mnemonicSize: Int,
                     mnemonicPassphrase: Option[String]): Future[Either[String, Mnemonic]] =
      Future.successful(
        Mnemonic
          .generate(mnemonicSize)
          .toRight("Cannot generate mnemonic")
          .map { mnemonic =>
            val seed = mnemonic.toSeed(mnemonicPassphrase.getOrElse(""))
            maybeSecretStorage = Option(SecretStorage(seed, password, secretDir))
            mnemonic
          }
      )

    def restoreWallet(password: String,
                      mnemonic: String,
                      mnemonicPassphrase: Option[String]): Future[Either[String, Unit]] = {
      Future.successful {
        val words = mnemonic.split(' ').toSeq
        Mnemonic.fromWords(words).map(_.toSeed(mnemonicPassphrase.getOrElse(""))) match {
          case Some(seed) =>
            maybeSecretStorage = Option(SecretStorage(seed, password, secretDir))
            Right(())
          case None =>
            Left(s"Invalid mnemonic: $mnemonic")
        }
      }
    }

    def lockWallet(): Future[Either[String, Unit]] =
      Future.successful {
        Right(maybeSecretStorage.foreach(_.lock()))
      }

    def unlockWallet(password: String): Future[Either[String, Unit]] =
      withWallet(secretStorage => Future.successful(secretStorage.unlock(password)))

    def getBalance(): Future[Either[String, Long]] =
      withAddress { address =>
        blockFlowClient.getBalance(address)
      }

    def getAddress(): Future[Either[String, String]] =
      withAddress { address =>
        Future.successful(Right(address))
      }

    def transfer(address: String, amount: Long): Future[Either[String, String]] = {
      withPrivateKey { privateKey =>
        val pubKey = privateKey.extendedPublicKey.publicKey
        blockFlowClient.prepareTransaction(pubKey.toHexString, address, amount).flatMap {
          case Left(error) => Future.successful(Left(error))
          case Right(createTxResult) =>
            val message   = Hex.unsafe(createTxResult.hash)
            val signature = ALFSignatureSchema.sign(message, privateKey.privateKey)
            blockFlowClient
              .sendTransaction(createTxResult.unsignedTx, signature)
              .map(_.map(_.txId))
        }
      }
    }

    private def withWallet[A](
        f: SecretStorage => Future[Either[String, A]]): Future[Either[String, A]] =
      maybeSecretStorage match {
        case None                => Future.successful(Left("No wallet loaded"))
        case Some(secretStorage) => f(secretStorage)
      }

    private def withPrivateKey[A](
        f: ExtendedPrivateKey => Future[Either[String, A]]): Future[Either[String, A]] =
      withWallet(_.getPrivateKey() match {
        case None             => Future.successful(Left("Wallet locked"))
        case Some(privateKey) => f(privateKey)
      })

    private def withAddress[A](f: String => Future[Either[String, A]]): Future[Either[String, A]] =
      withPrivateKey { privateKey =>
        val address = LockupScript.p2pkh(privateKey.extendedPublicKey.publicKey).toBase58
        f(address)
      }
  }
}
