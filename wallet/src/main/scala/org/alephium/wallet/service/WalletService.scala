package org.alephium.wallet.service

import java.nio.file.Path

import scala.concurrent.{ExecutionContext, Future}

import org.alephium.crypto.wallet.BIP32.ExtendedPrivateKey
import org.alephium.crypto.wallet.Mnemonic
import org.alephium.protocol.SignatureSchema
import org.alephium.protocol.model.{Address, NetworkType}
import org.alephium.util.Hex
import org.alephium.util.{Hex, U64}
import org.alephium.wallet.storage.SecretStorage
import org.alephium.wallet.web.BlockFlowClient

trait WalletService {
  import WalletService._

  def createWallet(password: String,
                   mnemonicSize: Mnemonic.Size,
                   mnemonicPassphrase: Option[String]): Future[Either[WalletError, Mnemonic]]

  def restoreWallet(password: String,
                    mnemonic: String,
                    mnemonicPassphrase: Option[String]): Future[Either[WalletError, Unit]]

  def lockWallet(): Future[Either[WalletError, Unit]]
  def unlockWallet(password: String): Future[Either[WalletError, Unit]]
  def getBalance(): Future[Either[WalletError, Long]]
  def getAddress(): Future[Either[WalletError, Address]]
  def transfer(address: Address, amount: U64): Future[Either[WalletError, String]]
}

object WalletService {

  sealed trait WalletError {
    def message: String
  }

  final case class InvalidMnemonic(words: String) extends WalletError {
    val message: String = s"Invalid mnemonic: $words"
  }

  final case class CannotCreateEncryptedFile(directory: Path, storageMessage: String)
      extends WalletError {
    val message: String = s"Cannot create encrypted file at $directory ($storageMessage)"
  }

  case object NoWalletLoaded extends WalletError {
    val message: String = s"No wallet loaded"
  }

  case object WalletLocked extends WalletError {
    val message: String = s"Wallet is locked"
  }

  case object InvalidPassword extends WalletError {
    val message: String = s"Invalid password"
  }

  final case class BlockFlowClientError(message: String) extends WalletError

  def apply(blockFlowClient: BlockFlowClient, secretDir: Path, networkType: NetworkType)(
      implicit executionContext: ExecutionContext): WalletService =
    new Impl(blockFlowClient, secretDir, networkType)

  private var maybeSecretStorage: Option[SecretStorage] = None

  private class Impl(blockFlowClient: BlockFlowClient, secretDir: Path, networkType: NetworkType)(
      implicit executionContext: ExecutionContext)
      extends WalletService {
    def createWallet(password: String,
                     mnemonicSize: Mnemonic.Size,
                     mnemonicPassphrase: Option[String]): Future[Either[WalletError, Mnemonic]] =
      Future.successful(
        for {
          mnemonic <- Right(Mnemonic.generate(mnemonicSize))
          seed = mnemonic.toSeed(mnemonicPassphrase.getOrElse(""))
          storage <- SecretStorage(seed, password, secretDir).left
            .map(CannotCreateEncryptedFile(secretDir, _))
        } yield {
          maybeSecretStorage = Option(storage)
          mnemonic
        }
      )

    def restoreWallet(password: String,
                      mnemonic: String,
                      mnemonicPassphrase: Option[String]): Future[Either[WalletError, Unit]] = {
      Future.successful {
        val words = mnemonic.split(' ').toSeq
        Mnemonic.fromWords(words).map(_.toSeed(mnemonicPassphrase.getOrElse(""))) match {
          case Some(seed) =>
            SecretStorage(seed, password, secretDir)
              .map { storage =>
                maybeSecretStorage = Option(storage)
              }
              .left
              .map(CannotCreateEncryptedFile(secretDir, _))
          case None =>
            Left(InvalidMnemonic(mnemonic))
        }
      }
    }

    def lockWallet(): Future[Either[WalletError, Unit]] =
      Future.successful {
        Right(maybeSecretStorage.foreach(_.lock()))
      }

    def unlockWallet(password: String): Future[Either[WalletError, Unit]] =
      withWallet(secretStorage =>
        Future.successful(secretStorage.unlock(password).left.map(_ => InvalidPassword)))

    def getBalance(): Future[Either[WalletError, Long]] =
      withAddress { address =>
        blockFlowClient.getBalance(address).map(_.left.map(BlockFlowClientError))
      }

    def getAddress(): Future[Either[WalletError, Address]] =
      withAddress { address =>
        Future.successful(Right(address))
      }

    def transfer(address: Address, amount: U64): Future[Either[WalletError, String]] = {
      withPrivateKey { privateKey =>
        val pubKey = privateKey.publicKey
        blockFlowClient.prepareTransaction(pubKey.toHexString, address, amount).flatMap {
          case Left(error) => Future.successful(Left(BlockFlowClientError(error)))
          case Right(createTxResult) =>
            val message   = Hex.unsafe(createTxResult.hash)
            val signature = SignatureSchema.sign(message, privateKey.privateKey)
            blockFlowClient
              .sendTransaction(createTxResult.unsignedTx, signature, createTxResult.fromGroup)
              .map(_.map(_.txId))
              .map(_.left.map(BlockFlowClientError))
        }
      }
    }

    private def withWallet[A](
        f: SecretStorage => Future[Either[WalletError, A]]): Future[Either[WalletError, A]] =
      maybeSecretStorage match {
        case None                => Future.successful(Left(NoWalletLoaded))
        case Some(secretStorage) => f(secretStorage)
      }

    private def withPrivateKey[A](
        f: ExtendedPrivateKey => Future[Either[WalletError, A]]): Future[Either[WalletError, A]] =
      withWallet(_.getPrivateKey() match {
        case None             => Future.successful(Left(WalletLocked))
        case Some(privateKey) => f(privateKey)
      })

    private def withAddress[A](
        f: Address => Future[Either[WalletError, A]]): Future[Either[WalletError, A]] =
      withPrivateKey { privateKey =>
        val address = Address.p2pkh(networkType, privateKey.publicKey)
        f(address)
      }
  }
}
