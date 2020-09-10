package org.alephium.wallet.service

import java.nio.file.Path

import scala.concurrent.{ExecutionContext, Future}

import org.alephium.crypto.wallet.BIP32.ExtendedPrivateKey
import org.alephium.crypto.wallet.Mnemonic
import org.alephium.protocol.SignatureSchema
import org.alephium.protocol.model.{Address, NetworkType}
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
  def getAddress(): Future[Either[String, Address]]
  def transfer(address: Address, amount: Long): Future[Either[String, String]]
}

object WalletService {
  def apply(blockFlowClient: BlockFlowClient, secretDir: Path, networkType: NetworkType)(
      implicit executionContext: ExecutionContext): WalletService =
    new Impl(blockFlowClient, secretDir, networkType)

  private var maybeSecretStorage: Option[SecretStorage] = None

  private class Impl(blockFlowClient: BlockFlowClient, secretDir: Path, networkType: NetworkType)(
      implicit executionContext: ExecutionContext)
      extends WalletService {
    def createWallet(password: String,
                     mnemonicSize: Int,
                     mnemonicPassphrase: Option[String]): Future[Either[String, Mnemonic]] =
      Future.successful(
        for {
          mnemonic <- Mnemonic.generate(mnemonicSize).toRight("Cannot generate mnemonic")
          seed = mnemonic.toSeed(mnemonicPassphrase.getOrElse(""))
          storage <- SecretStorage(seed, password, secretDir)
        } yield {
          maybeSecretStorage = Option(storage)
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
            SecretStorage(seed, password, secretDir).map { storage =>
              maybeSecretStorage = Option(storage)
            }
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

    def getAddress(): Future[Either[String, Address]] =
      withAddress { address =>
        Future.successful(Right(address))
      }

    def transfer(address: Address, amount: Long): Future[Either[String, String]] = {
      withPrivateKey { privateKey =>
        val pubKey = privateKey.publicKey
        blockFlowClient.prepareTransaction(pubKey.toHexString, address, amount).flatMap {
          case Left(error) => Future.successful(Left(error))
          case Right(createTxResult) =>
            val message   = Hex.unsafe(createTxResult.hash)
            val signature = SignatureSchema.sign(message, privateKey.privateKey)
            blockFlowClient
              .sendTransaction(createTxResult.unsignedTx, signature, createTxResult.fromGroup)
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

    private def withAddress[A](f: Address => Future[Either[String, A]]): Future[Either[String, A]] =
      withPrivateKey { privateKey =>
        val address = Address.p2pkh(networkType, privateKey.publicKey)
        f(address)
      }
  }
}
