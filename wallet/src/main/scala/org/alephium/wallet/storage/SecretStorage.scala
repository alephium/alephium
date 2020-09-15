package org.alephium.wallet.storage

import java.io.{File, PrintWriter}
import java.nio.file.{Files, Path}
import java.util.UUID

import scala.io.Source
import scala.util.{Try, Using}

import akka.util.ByteString
import io.circe.Codec
import io.circe.generic.semiauto.deriveCodec
import io.circe.parser.decode
import io.circe.syntax._

import org.alephium.crypto.AES
import org.alephium.crypto.wallet.BIP32
import org.alephium.crypto.wallet.BIP32.ExtendedPrivateKey
import org.alephium.serde.{deserialize, serialize, Serde}
import org.alephium.util.AVector
import org.alephium.wallet.Constants
import org.alephium.wallet.circe.UtilCodecs

trait SecretStorage {
  def lock(): Unit
  def unlock(password: String): Either[SecretStorage.SecretStorageError, Unit]
  def getPrivateKey(): Either[SecretStorage.SecretStorageError, ExtendedPrivateKey]
  def deriveNextKey(): Either[SecretStorage.SecretStorageError, ExtendedPrivateKey]
}

object SecretStorage extends UtilCodecs {

  sealed trait SecretStorageError

  case object Locked              extends SecretStorageError
  case object CannotDeriveKey     extends SecretStorageError
  case object CannotParseFile     extends SecretStorageError
  case object CannotDecryptSecret extends SecretStorageError
  case object InvalidState        extends SecretStorageError
  case object SecretDirError      extends SecretStorageError
  case object SecretFileError     extends SecretStorageError

  final case class StoredState(seed: ByteString, numberOfAddresses: Int)

  object StoredState {
    implicit val serde: Serde[StoredState] =
      Serde.forProduct2(apply, state => (state.seed, state.numberOfAddresses))
  }

  private final case class State(seed: ByteString,
                                 password: String,
                                 privateKeys: AVector[ExtendedPrivateKey])

  implicit val codec: Codec[AES.Encrypted] = deriveCodec[AES.Encrypted]

  def fromFile(file: File, password: String): Either[SecretStorageError, SecretStorage] = {
    for {
      _ <- stateFromFile(file, password)
    } yield {
      new Impl(file)
    }
  }

  def apply(seed: ByteString,
            password: String,
            secretDir: Path): Either[SecretStorageError, SecretStorage] = {

    val uuid = UUID.nameUUIDFromBytes(seed.toArray)
    val file = new File(s"$secretDir/$uuid.json")

    if (file.exists) {
      fromFile(file, password)
    } else {
      for {
        _ <- Try(Files.createDirectories(secretDir)).toEither.left.map(_ => SecretDirError)
        _ <- storeStateToFile(file, StoredState(seed, numberOfAddresses = 1), password)
      } yield {
        new Impl(file)
      }
    }
  }

  //TODO add some `synchronized` for the state
  private class Impl(file: File) extends SecretStorage {

    private var maybeState: Option[State] = None

    override def lock(): Unit = {
      maybeState = None
    }

    override def unlock(password: String): Either[SecretStorageError, Unit] = {
      for {
        state <- stateFromFile(file, password)
      } yield {
        maybeState = Some(state)
      }
    }

    override def getPrivateKey(): Either[SecretStorageError, ExtendedPrivateKey] = {
      for {
        state      <- maybeState.toRight(Locked: SecretStorageError)
        privateKey <- state.privateKeys.lastOption.toRight(InvalidState: SecretStorageError)
      } yield {
        privateKey
      }
    }

    override def deriveNextKey(): Either[SecretStorageError, ExtendedPrivateKey] = {
      for {
        state             <- maybeState.toRight(Locked)
        currentPrivateKey <- state.privateKeys.lastOption.toRight(InvalidState)
        newPrivateKey     <- deriveNextPrivateKey(state.seed, currentPrivateKey)
        keys = state.privateKeys :+ newPrivateKey
        _ <- storeStateToFile(file, StoredState(state.seed, keys.length), state.password)
      } yield {
        maybeState = Some(State(state.seed, state.password, keys))
        newPrivateKey
      }
    }
  }

  private def stateFromFile(file: File, password: String): Either[SecretStorageError, State] = {
    Using(Source.fromFile(file)) { source =>
      val rawFile = source.getLines().mkString
      for {
        encrypted   <- decode[AES.Encrypted](rawFile).left.map(_ => CannotParseFile)
        stateBytes  <- AES.decrypt(encrypted, password).toEither.left.map(_ => CannotDecryptSecret)
        state       <- deserialize[StoredState](stateBytes).left.map(_ => SecretFileError)
        privateKeys <- deriveKeys(state.seed, state.numberOfAddresses)
      } yield {
        State(state.seed, password, privateKeys)
      }
    }.toEither.left.map(_ => SecretFileError).flatten
  }

  private def deriveKeys(seed: ByteString,
                         number: Int): Either[SecretStorageError, AVector[ExtendedPrivateKey]] = {
    if (number <= 0) {
      Right(AVector.empty)
    } else {
      BIP32
        .btcMasterKey(seed)
        .derive(Constants.path.toSeq)
        .toRight(CannotDeriveKey)
        .flatMap { firstKey =>
          if (number <= 1) {
            Right(AVector(firstKey))
          } else {
            AVector
              .from((2 to number))
              .foldE((firstKey, AVector(firstKey))) {
                case ((prevKey, keys), _) =>
                  deriveNextPrivateKey(seed, prevKey).map { newKey =>
                    (newKey, keys :+ newKey)
                  }
              }
              .map { case (_, keys) => keys }
          }
        }
    }
  }

  private def deriveNextPrivateKey(
      seed: ByteString,
      privateKey: ExtendedPrivateKey): Either[SecretStorageError, ExtendedPrivateKey] =
    (for {
      index  <- privateKey.path.lastOption.map(_ + 1)
      parent <- BIP32.btcMasterKey(seed).derive(Constants.path.dropRight(1).toSeq)
      child  <- parent.derive(index)
    } yield child).toRight(CannotDeriveKey)

  private def storeStateToFile(file: File,
                               storedState: StoredState,
                               password: String): Either[SecretStorageError, Unit] = {
    Using
      .Manager { use =>
        val outWriter = use(new PrintWriter(file))
        val encrypted = AES.encrypt(serialize(storedState), password)
        outWriter.write(encryptedAsJson(encrypted))
      }
      .toEither
      .left
      .map(_ => SecretFileError)
  }

  private def encryptedAsJson(encrypted: AES.Encrypted) = {
    // scalastyle:off regex
    encrypted.asJson.noSpaces
    // scalastyle:on
  }
}
