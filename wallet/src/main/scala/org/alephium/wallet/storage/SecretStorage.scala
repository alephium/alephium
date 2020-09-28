package org.alephium.wallet.storage

import java.io.{File, PrintWriter}

import scala.io.Source
import scala.util.Using

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
  def unlock(password: String): Either[SecretStorage.Error, Unit]
  def isLocked(): Boolean
  def getCurrentPrivateKey(): Either[SecretStorage.Error, ExtendedPrivateKey]
  def getAllPrivateKeys()
    : Either[SecretStorage.Error, (ExtendedPrivateKey, AVector[ExtendedPrivateKey])]
  def deriveNextKey(): Either[SecretStorage.Error, ExtendedPrivateKey]
  def changeActiveKey(key: ExtendedPrivateKey): Either[SecretStorage.Error, Unit]
}

object SecretStorage extends UtilCodecs {

  sealed trait Error

  case object Locked                  extends Error
  case object CannotDeriveKey         extends Error
  case object CannotParseFile         extends Error
  case object CannotDecryptSecret     extends Error
  case object InvalidState            extends Error
  case object SecretDirError          extends Error
  case object SecretFileError         extends Error
  case object SecretFileAlreadyExists extends Error
  case object UnknownKey              extends Error

  final case class StoredState(seed: ByteString, numberOfAddresses: Int, activeAddressIndex: Int)

  object StoredState {
    implicit val serde: Serde[StoredState] =
      Serde.forProduct3(apply,
                        state => (state.seed, state.numberOfAddresses, state.activeAddressIndex))
  }

  private final case class State(seed: ByteString,
                                 password: String,
                                 activeKey: ExtendedPrivateKey,
                                 privateKeys: AVector[ExtendedPrivateKey])

  private final case class SecretFile(encrypted: ByteString,
                                      salt: ByteString,
                                      iv: ByteString,
                                      version: Int) {
    def toAESEncrytped: AES.Encrypted = AES.Encrypted(encrypted, salt, iv)
  }

  private implicit val codec: Codec[SecretFile] = deriveCodec[SecretFile]

  def load(file: File): Either[Error, SecretStorage] = {
    Using(Source.fromFile(file)) { source =>
      val rawFile = source.getLines().mkString
      for {
        _ <- decode[SecretFile](rawFile).left.map(_ => CannotParseFile)
      } yield {
        new Impl(file, None)
      }
    }.toEither.left
      .map(_ => SecretFileError)
      .flatten
  }

  def create(seed: ByteString, password: String, file: File): Either[Error, SecretStorage] = {
    if (file.exists) {
      Left(SecretFileAlreadyExists)
    } else {
      for {
        _ <- storeStateToFile(file,
                              StoredState(seed, numberOfAddresses = 1, activeAddressIndex = 0),
                              password)
        state <- stateFromFile(file, password)
      } yield {
        new Impl(file, Some(state))
      }
    }
  }

  private[storage] def fromFile(file: File, password: String): Either[Error, SecretStorage] = {
    for {
      state <- stateFromFile(file, password)
    } yield {
      new Impl(file, Some(state))
    }
  }

  //TODO add some `synchronized` for the state
  private class Impl(file: File, initialState: Option[State]) extends SecretStorage {

    private var maybeState: Option[State] = initialState

    override def lock(): Unit = {
      maybeState = None
    }

    override def unlock(password: String): Either[Error, Unit] = {
      for {
        state <- stateFromFile(file, password)
      } yield {
        maybeState = Some(state)
      }
    }

    override def isLocked(): Boolean = maybeState.isEmpty

    override def getCurrentPrivateKey(): Either[Error, ExtendedPrivateKey] = {
      for {
        state <- getState
      } yield {
        state.activeKey
      }
    }

    def getAllPrivateKeys(): Either[Error, (ExtendedPrivateKey, AVector[ExtendedPrivateKey])] = {
      for {
        state <- getState
      } yield {
        (state.activeKey, state.privateKeys)
      }
    }
    override def deriveNextKey(): Either[Error, ExtendedPrivateKey] = {
      for {
        state         <- getState
        latestKey     <- state.privateKeys.lastOption.toRight(InvalidState)
        newPrivateKey <- deriveNextPrivateKey(state.seed, latestKey)
        keys = state.privateKeys :+ newPrivateKey
        _ <- storeStateToFile(file,
                              StoredState(state.seed, keys.length, keys.length - 1),
                              state.password)
      } yield {
        maybeState = Some(State(state.seed, state.password, newPrivateKey, keys))
        newPrivateKey
      }
    }

    override def changeActiveKey(key: ExtendedPrivateKey): Either[SecretStorage.Error, Unit] = {
      for {
        state <- getState
        index = state.privateKeys.indexWhere(_.privateKey == key.privateKey)
        _ <- Either.cond(index >= 0, (), UnknownKey)
        _ <- storeStateToFile(file,
                              StoredState(state.seed, state.privateKeys.length, index),
                              state.password)
      } yield {
        maybeState = Some(state.copy(activeKey = key))
        ()
      }
    }

    private def getState: Either[Error, State] = maybeState.toRight(Locked)
  }

  private def stateFromFile(file: File, password: String): Either[Error, State] = {
    Using(Source.fromFile(file)) { source =>
      val rawFile = source.getLines().mkString
      for {
        secretFile <- decode[SecretFile](rawFile).left.map(_ => CannotParseFile)
        stateBytes <- AES
          .decrypt(secretFile.toAESEncrytped, password)
          .toEither
          .left
          .map(_ => CannotDecryptSecret)
        state       <- deserialize[StoredState](stateBytes).left.map(_ => SecretFileError)
        privateKeys <- deriveKeys(state.seed, state.numberOfAddresses)
        active      <- privateKeys.get(state.activeAddressIndex).toRight(InvalidState)
      } yield {
        State(state.seed, password, active, privateKeys)
      }
    }.toEither.left.map(_ => SecretFileError).flatten
  }

  private def deriveKeys(seed: ByteString,
                         number: Int): Either[Error, AVector[ExtendedPrivateKey]] = {
    if (number <= 0) {
      Right(AVector.empty)
    } else {
      for {
        rootPrivateKey <- BIP32
          .btcMasterKey(seed)
          .derive(Constants.path.init)
          .toRight(CannotDeriveKey)
        privateKeys <- AVector.from(0 until number).mapE { index =>
          rootPrivateKey.derive(index).toRight(CannotDeriveKey)
        }
      } yield privateKeys
    }
  }

  private def deriveNextPrivateKey(
      seed: ByteString,
      privateKey: ExtendedPrivateKey): Either[Error, ExtendedPrivateKey] =
    (for {
      index  <- privateKey.path.lastOption.map(_ + 1)
      parent <- BIP32.btcMasterKey(seed).derive(Constants.path.init)
      child  <- parent.derive(index)
    } yield child).toRight(CannotDeriveKey)

  private def storeStateToFile(file: File,
                               storedState: StoredState,
                               password: String): Either[Error, Unit] = {
    Using
      .Manager { use =>
        val outWriter = use(new PrintWriter(file))
        val encrypted = AES.encrypt(serialize(storedState), password)
        val secretFile =
          SecretFile(encrypted.encrypted, encrypted.salt, encrypted.iv, Constants.walletFileVersion)
        outWriter.write(secretFileAsJson(secretFile))
      }
      .toEither
      .left
      .map(_ => SecretFileError)
  }

  private def secretFileAsJson(secretFile: SecretFile) = {
    // scalastyle:off regex
    secretFile.asJson.noSpaces
    // scalastyle:on
  }
}
