package org.alephium.wallet.service

import java.io.File
import java.nio.file.Files
import java.nio.file.Path

import scala.collection.mutable
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

  def createWallet(
      password: String,
      mnemonicSize: Mnemonic.Size,
      walletName: Option[String],
      mnemonicPassphrase: Option[String]): Future[Either[WalletError, (String, Mnemonic)]]

  def restoreWallet(password: String,
                    mnemonic: Mnemonic,
                    walletName: Option[String],
                    mnemonicPassphrase: Option[String]): Future[Either[WalletError, String]]

  def lockWallet(wallet: String): Future[Either[WalletError, Unit]]
  def unlockWallet(wallet: String, password: String): Future[Either[WalletError, Unit]]
  def getBalances(wallet: String): Future[Either[WalletError, AVector[(Address, U64)]]]
  def getAddresses(wallet: String): Future[Either[WalletError, (Address, AVector[Address])]]
  def transfer(wallet: String, address: Address, amount: U64): Future[Either[WalletError, Hash]]
  def deriveNextAddress(wallet: String): Future[Either[WalletError, Address]]
  def changeActiveAddress(wallet: String, address: Address): Future[Either[WalletError, Unit]]
  def listWallets(): Future[Either[WalletError, AVector[String]]]
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

  case object UnexpectedError extends WalletError {
    val message: String = s"Unexpected error"
  }

  final case class BlockFlowClientError(message: String) extends WalletError

  def apply(blockFlowClient: BlockFlowClient, secretDir: Path, networkType: NetworkType)(
      implicit executionContext: ExecutionContext): WalletService = {

    Files.createDirectories(secretDir)

    new Impl(blockFlowClient, secretDir, networkType)
  }

  private val secretStorages: mutable.Map[String, SecretStorage] = mutable.Map.empty

  private class Impl(blockFlowClient: BlockFlowClient, secretDir: Path, networkType: NetworkType)(
      implicit executionContext: ExecutionContext)
      extends WalletService {

    override def createWallet(
        password: String,
        mnemonicSize: Mnemonic.Size,
        walletName: Option[String],
        mnemonicPassphrase: Option[String]): Future[Either[WalletError, (String, Mnemonic)]] =
      Future.successful(
        for {
          mnemonic <- Right(Mnemonic.generate(mnemonicSize))
          seed = mnemonic.toSeed(mnemonicPassphrase.getOrElse(""))
          file <- buildWalletFile(walletName)
          storage <- SecretStorage
            .create(seed, password, file)
            .left
            .map(_ => CannotCreateEncryptedFile(secretDir))
        } yield {
          val fileName = file.getName
          secretStorages.addOne(fileName -> storage)
          (fileName, mnemonic)
        }
      )

    override def restoreWallet(
        password: String,
        mnemonic: Mnemonic,
        walletName: Option[String],
        mnemonicPassphrase: Option[String]): Future[Either[WalletError, String]] = {
      Future.successful {
        val seed = mnemonic.toSeed(mnemonicPassphrase.getOrElse(""))
        for {
          file <- buildWalletFile(walletName)
          storage <- SecretStorage
            .create(seed, password, file)
            .left
            .map(_ => CannotCreateEncryptedFile(secretDir))
        } yield {
          val fileName = file.getName
          secretStorages.addOne(file.getName -> storage)
          fileName
        }
      }
    }

    override def lockWallet(wallet: String): Future[Either[WalletError, Unit]] =
      Future.successful {
        Right(secretStorages.get(wallet).foreach(_.lock()))
      }

    override def unlockWallet(wallet: String, password: String): Future[Either[WalletError, Unit]] =
      withWallet(wallet)(secretStorage =>
        Future.successful(secretStorage.unlock(password).left.map(_ => InvalidPassword)))

    override def getBalances(wallet: String): Future[Either[WalletError, AVector[(Address, U64)]]] =
      withAddresses(wallet) {
        case (_, addresses) =>
          Future
            .sequence(
              addresses.toSeq.map(getBalance)
            )
            .map(AVector.from(_).mapE(identity))
      }

    override def getAddresses(
        wallet: String): Future[Either[WalletError, (Address, AVector[Address])]] =
      withAddresses(wallet) { addresses =>
        Future.successful(Right(addresses))
      }

    override def transfer(wallet: String,
                          address: Address,
                          amount: U64): Future[Either[WalletError, Hash]] = {
      withPrivateKey(wallet) { privateKey =>
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

    override def deriveNextAddress(wallet: String): Future[Either[WalletError, Address]] = {
      withWallet(wallet) { secretStorage =>
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

    override def changeActiveAddress(wallet: String,
                                     address: Address): Future[Either[WalletError, Unit]] = {
      withWallet(wallet) { secretStorage =>
        withPrivateKeys(wallet) {
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

    override def listWallets(): Future[Either[WalletError, AVector[String]]] = {
      Future.successful(listWalletsInSecretDir())
    }

    private def listWalletsInSecretDir(): Either[WalletError, AVector[String]] = {
      val dir = secretDir.toFile
      Either.cond(dir.exists && dir.isDirectory,
                  AVector.from(dir.listFiles.filter(_.isFile).map(_.getName)),
                  UnexpectedError)
    }

    private def getBalance(address: Address): Future[Either[WalletError, (Address, U64)]] = {
      blockFlowClient
        .getBalance(address)
        .map(_.map(amount => (address, amount)).left.map(message =>
          BlockFlowClientError(message): WalletError))
    }

    private def withWallet[A](wallet: String)(
        f: SecretStorage => Future[Either[WalletError, A]]): Future[Either[WalletError, A]] = {
      secretStorages.get(wallet) match {
        case None =>
          val file = new File(s"$secretDir/$wallet")
          SecretStorage.load(file) match {
            case Right(secretStorage) =>
              secretStorages.addOne(wallet -> secretStorage)
              f(secretStorage)
            case Left(_) =>
              Future.successful(Left(NoWalletLoaded))
          }

        case Some(secretStorage) => f(secretStorage)
      }
    }

    private def withPrivateKey[A](wallet: String)(
        f: ExtendedPrivateKey => Future[Either[WalletError, A]]): Future[Either[WalletError, A]] =
      withWallet(wallet)(_.getCurrentPrivateKey() match {
        case Left(_)           => Future.successful(Left(WalletLocked))
        case Right(privateKey) => f(privateKey)
      })

    private def withPrivateKeys[A](wallet: String)(
        f: ((ExtendedPrivateKey, AVector[ExtendedPrivateKey])) => Future[Either[WalletError, A]])
      : Future[Either[WalletError, A]] =
      withWallet(wallet)(_.getAllPrivateKeys() match {
        case Left(_)            => Future.successful(Left(WalletLocked))
        case Right(privateKeys) => f(privateKeys)
      })

    private def withAddresses[A](wallet: String)(
        f: ((Address, AVector[Address])) => Future[Either[WalletError, A]])
      : Future[Either[WalletError, A]] =
      withPrivateKeys(wallet) {
        case (active, privateKeys) =>
          val activeAddress = Address.p2pkh(networkType, active.publicKey)
          val addresses =
            privateKeys.map(privateKey => Address.p2pkh(networkType, privateKey.publicKey))
          f((activeAddress, addresses))
      }

    private def buildWalletFile(walletName: Option[String]): Either[WalletError, File] = {
      for {
        currentWallets <- listWalletsInSecretDir()
      } yield {
        val name = walletName.getOrElse(s"wallet-${currentWallets.length}")
        new File(s"$secretDir/$name")
      }
    }
  }
}
