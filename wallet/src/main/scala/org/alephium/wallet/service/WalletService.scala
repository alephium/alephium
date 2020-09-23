package org.alephium.wallet.service

import java.nio.file.Path

import scala.concurrent.{ExecutionContext, Future}

import org.alephium.crypto.wallet.BIP32.ExtendedPrivateKey
import org.alephium.crypto.wallet.Mnemonic
import org.alephium.protocol.{Hash, SignatureSchema}
import org.alephium.protocol.model.{Address, NetworkType}
import org.alephium.util.{AVector, Hex, U64}
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
  def getBalances(): Future[Either[WalletError, AVector[(Address, U64)]]]
  def getAddresses(): Future[Either[WalletError, (Address, AVector[Address])]]
  def transfer(address: Address, amount: U64): Future[Either[WalletError, Hash]]
  def deriveNextAddress(): Future[Either[WalletError, Address]]
  def changeActiveAddress(address: Address): Future[Either[WalletError, Unit]]
}

object WalletService {

  sealed trait WalletError {
    def message: String
  }

  final case class InvalidMnemonic(words: String) extends WalletError {
    val message: String = s"Invalid mnemonic: $words"
  }

  final case class CannotCreateEncryptedFile(directory: Path) extends WalletError {
    val message: String = s"Cannot create encrypted file at $directory"
  }

  final case class UnknownAddress(address: Address) extends WalletError {
    val message: String = s"Unknown address: ${address.toBase58}"
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

  case object CannotDeriveNewAddress extends WalletError {
    val message: String = s"Cannot derive new address"
  }

  final case class BlockFlowClientError(message: String) extends WalletError

  def apply(blockFlowClient: BlockFlowClient, secretDir: Path, networkType: NetworkType)(
      implicit executionContext: ExecutionContext): WalletService =
    new Impl(blockFlowClient, secretDir, networkType)

  private var maybeSecretStorage: Option[SecretStorage] = None

  private class Impl(blockFlowClient: BlockFlowClient, secretDir: Path, networkType: NetworkType)(
      implicit executionContext: ExecutionContext)
      extends WalletService {

    override def createWallet(
        password: String,
        mnemonicSize: Mnemonic.Size,
        mnemonicPassphrase: Option[String]): Future[Either[WalletError, Mnemonic]] =
      Future.successful(
        for {
          mnemonic <- Right(Mnemonic.generate(mnemonicSize))
          seed = mnemonic.toSeed(mnemonicPassphrase.getOrElse(""))
          storage <- SecretStorage
            .create(seed, password, secretDir)
            .left
            .map(_ => CannotCreateEncryptedFile(secretDir))
        } yield {
          maybeSecretStorage = Option(storage)
          mnemonic
        }
      )

    override def restoreWallet(
        password: String,
        mnemonic: String,
        mnemonicPassphrase: Option[String]): Future[Either[WalletError, Unit]] = {
      Future.successful {
        val words = AVector.unsafe(mnemonic.split(' '))
        Mnemonic.fromWords(words).map(_.toSeed(mnemonicPassphrase.getOrElse(""))) match {
          case Some(seed) =>
            SecretStorage
              .load(seed, password, secretDir)
              .map { storage =>
                maybeSecretStorage = Option(storage)
              }
              .left
              .map(_ => CannotCreateEncryptedFile(secretDir))
          case None =>
            Left(InvalidMnemonic(mnemonic))
        }
      }
    }

    override def lockWallet(): Future[Either[WalletError, Unit]] =
      Future.successful {
        Right(maybeSecretStorage.foreach(_.lock()))
      }

    override def unlockWallet(password: String): Future[Either[WalletError, Unit]] =
      withWallet(secretStorage =>
        Future.successful(secretStorage.unlock(password).left.map(_ => InvalidPassword)))

    override def getBalances(): Future[Either[WalletError, AVector[(Address, U64)]]] =
      withAddresses {
        case (_, addresses) =>
          Future
            .sequence(
              addresses.toSeq.map(getBalance)
            )
            .map(AVector.from(_).mapE(identity))
      }

    override def getAddresses(): Future[Either[WalletError, (Address, AVector[Address])]] =
      withAddresses { addresses =>
        Future.successful(Right(addresses))
      }

    override def transfer(address: Address, amount: U64): Future[Either[WalletError, Hash]] = {
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

    override def deriveNextAddress(): Future[Either[WalletError, Address]] = {
      withWallet { secretStorage =>
        Future.successful {
          secretStorage
            .deriveNextKey()
            .left
            .map {
              case SecretStorage.Locked          => WalletLocked: WalletError
              case SecretStorage.CannotDeriveKey => CannotDeriveNewAddress: WalletError
              case SecretStorage.SecretFileError => CannotDeriveNewAddress: WalletError
              case _                             => CannotDeriveNewAddress: WalletError
            }
            .map { privateKey =>
              Address.p2pkh(networkType, privateKey.extendedPublicKey.publicKey)
            }
        }
      }
    }

    override def changeActiveAddress(address: Address): Future[Either[WalletError, Unit]] = {
      withWallet { secretStorage =>
        withPrivateKeys {
          case (_, privateKeys) =>
            Future.successful {
              (for {
                privateKey <- privateKeys.find(privateKey =>
                  Address.p2pkh(networkType, privateKey.publicKey) == address)
                _ <- secretStorage.changeActiveKey(privateKey).toOption
              } yield (())).toRight(UnknownAddress(address): WalletError)
            }
        }
      }
    }

    private def getBalance(address: Address): Future[Either[WalletError, (Address, U64)]] = {
      blockFlowClient
        .getBalance(address)
        .map(_.map(amount => (address, amount)).left.map(message =>
          BlockFlowClientError(message): WalletError))
    }

    private def withWallet[A](
        f: SecretStorage => Future[Either[WalletError, A]]): Future[Either[WalletError, A]] =
      maybeSecretStorage match {
        case None                => Future.successful(Left(NoWalletLoaded))
        case Some(secretStorage) => f(secretStorage)
      }

    private def withPrivateKey[A](
        f: ExtendedPrivateKey => Future[Either[WalletError, A]]): Future[Either[WalletError, A]] =
      withWallet(_.getCurrentPrivateKey() match {
        case Left(_)           => Future.successful(Left(WalletLocked))
        case Right(privateKey) => f(privateKey)
      })

    private def withPrivateKeys[A](
        f: ((ExtendedPrivateKey, AVector[ExtendedPrivateKey])) => Future[Either[WalletError, A]])
      : Future[Either[WalletError, A]] =
      withWallet(_.getAllPrivateKeys() match {
        case Left(_)            => Future.successful(Left(WalletLocked))
        case Right(privateKeys) => f(privateKeys)
      })

    private def withAddresses[A](f: ((Address, AVector[Address])) => Future[Either[WalletError, A]])
      : Future[Either[WalletError, A]] =
      withPrivateKeys {
        case (active, privateKeys) =>
          val activeAddress = Address.p2pkh(networkType, active.publicKey)
          val addresses =
            privateKeys.map(privateKey => Address.p2pkh(networkType, privateKey.publicKey))
          f((activeAddress, addresses))
      }
  }
}
