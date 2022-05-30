// Copyright 2018 The Alephium Authors
// This file is part of the alephium project.
//
// The library is free software: you can redistribute it and/or modify
// it under the terms of the GNU Lesser General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// The library is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
// GNU Lesser General Public License for more details.
//
// You should have received a copy of the GNU Lesser General Public License
// along with the library. If not, see <http://www.gnu.org/licenses/>.

package org.alephium.wallet.storage

import java.io.{File, FileNotFoundException, PrintWriter}
import java.nio.file.Files

import scala.io.Source
import scala.util.{Try, Using}

import akka.util.ByteString

import org.alephium.api.UtilJson._
import org.alephium.crypto.{AES, Sha256}
import org.alephium.crypto.wallet.{BIP32, Mnemonic}
import org.alephium.crypto.wallet.BIP32.ExtendedPrivateKey
import org.alephium.json.Json._
import org.alephium.serde.{deserialize, serialize, Serde}
import org.alephium.util.AVector
import org.alephium.wallet.Constants

trait SecretStorage {
  def lock(): Unit
  def unlock(
      password: String,
      mnemonicPassphrase: Option[String]
  ): Either[SecretStorage.Error, Unit]
  def delete(password: String): Either[SecretStorage.Error, Unit]
  def isLocked(): Boolean
  def isMiner(): Either[SecretStorage.Error, Boolean]
  def getActivePrivateKey(): Either[SecretStorage.Error, ExtendedPrivateKey]
  def getAllPrivateKeys()
      : Either[SecretStorage.Error, (ExtendedPrivateKey, AVector[ExtendedPrivateKey])]
  def deriveNextKey(): Either[SecretStorage.Error, ExtendedPrivateKey]
  def changeActiveKey(key: ExtendedPrivateKey): Either[SecretStorage.Error, Unit]
  def revealMnemonic(password: String): Either[SecretStorage.Error, Mnemonic]
}

object SecretStorage {

  sealed trait Error

  case object Locked                    extends Error
  case object CannotDeriveKey           extends Error
  case object CannotParseFile           extends Error
  case object CannotDecryptSecret       extends Error
  case object InvalidState              extends Error
  case object SecretFileError           extends Error
  case object SecretFileAlreadyExists   extends Error
  case object UnknownKey                extends Error
  case object InvalidMnemonicPassphrase extends Error

  implicit private val mnemonicSerde: Serde[Mnemonic] = Serde.forProduct1(Mnemonic.unsafe, _.words)

  final case class SecretFileNotFound(file: File) extends Error

  final case class StoredState(
      mnemonic: Mnemonic,
      isMiner: Boolean,
      numberOfAddresses: Int,
      activeAddressIndex: Int,
      passphraseDoubleSha256: Option[Sha256]
  )

  object StoredState {
    implicit val serde: Serde[StoredState] =
      Serde.forProduct5(
        apply,
        state =>
          (
            state.mnemonic,
            state.isMiner,
            state.numberOfAddresses,
            state.activeAddressIndex,
            state.passphraseDoubleSha256
          )
      )

    def from(
        state: State,
        mnemonic: Mnemonic,
        passphraseDoubleSha256: Option[Sha256]
    ): Either[Error, StoredState] = {
      val index = state.privateKeys.indexWhere(_.privateKey == state.activeKey.privateKey)
      Either.cond(
        index >= 0,
        StoredState(
          mnemonic,
          state.isMiner,
          state.privateKeys.length,
          index,
          passphraseDoubleSha256
        ),
        UnknownKey
      )
    }
  }

  final private case class State(
      seed: ByteString,
      password: String,
      isMiner: Boolean,
      activeKey: ExtendedPrivateKey,
      privateKeys: AVector[ExtendedPrivateKey]
  )

  final private case class SecretFile(
      encrypted: ByteString,
      salt: ByteString,
      iv: ByteString,
      version: Int
  ) {
    def toAESEncrytped: AES.Encrypted = AES.Encrypted(encrypted, salt, iv)
  }

  @SuppressWarnings(Array("org.wartremover.warts.ToString"))
  implicit private val readWriter: ReadWriter[SecretFile] = macroRW[SecretFile]

  def load(file: File, path: AVector[Int]): Either[Error, SecretStorage] = {
    Using(Source.fromFile(file)("UTF-8")) { source =>
      val rawFile = source.getLines().mkString
      for {
        _ <- Try(read[SecretFile](rawFile)).toEither.left.map(_ => CannotParseFile)
      } yield {
        new Impl(file, None, path)
      }
    }.toEither.left.map {
      case _: FileNotFoundException => SecretFileNotFound(file)
      case _                        => SecretFileError: Error
    }.flatten
  }

  def create(
      mnemonic: Mnemonic,
      mnemonicPassphrase: Option[String],
      password: String,
      isMiner: Boolean,
      file: File,
      path: AVector[Int]
  ): Either[Error, SecretStorage] = {
    if (file.exists) {
      Left(SecretFileAlreadyExists)
    } else {
      val passphraseDoubleSha256 =
        mnemonicPassphrase.map(passphrase => Sha256.doubleHash(ByteString(passphrase)))
      for {
        _ <- storeStateToFile(
          file,
          StoredState(
            mnemonic,
            isMiner,
            numberOfAddresses = 1,
            activeAddressIndex = 0,
            passphraseDoubleSha256
          ),
          password
        )
        state <- stateFromFile(file, password, path, mnemonicPassphrase)
      } yield {
        new Impl(file, Some(state), path)
      }
    }
  }

  private[storage] def fromFile(
      file: File,
      password: String,
      path: AVector[Int],
      mnemonicPassphrase: Option[String]
  ): Either[Error, SecretStorage] = {
    for {
      state <- stateFromFile(file, password, path, mnemonicPassphrase)
    } yield {
      new Impl(file, Some(state), path)
    }
  }

  // TODO add some `synchronized` for the state
  private class Impl(file: File, initialState: Option[State], path: AVector[Int])
      extends SecretStorage {

    private var maybeState: Option[State] = initialState

    override def lock(): Unit = {
      if (!isLocked()) {
        maybeState = None
      }
    }

    override def unlock(
        password: String,
        mnemonicPassphrase: Option[String]
    ): Either[Error, Unit] = {
      for {
        state <- stateFromFile(file, password, path, mnemonicPassphrase)
      } yield {
        maybeState = Some(state)
      }
    }

    override def delete(password: String): Either[Error, Unit] = {
      for {
        _   <- validatePassword(file, password)
        res <- Try(Files.delete(file.toPath())).toEither.left.map(_ => SecretFileError)
      } yield {
        res
      }
    }

    override def isLocked(): Boolean = maybeState.isEmpty

    override def isMiner(): Either[Error, Boolean] = {
      for {
        state <- getState
      } yield {
        state.isMiner
      }
    }

    override def getActivePrivateKey(): Either[Error, ExtendedPrivateKey] = {
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
        _ <- updateState(state.copy(activeKey = newPrivateKey, privateKeys = keys))
      } yield {
        newPrivateKey
      }
    }

    override def changeActiveKey(key: ExtendedPrivateKey): Either[SecretStorage.Error, Unit] = {
      for {
        state <- getState
        _     <- updateState(state.copy(activeKey = key))
      } yield {
        ()
      }
    }

    private def updateState(state: State): Either[SecretStorage.Error, Unit] = {
      synchronized {
        for {
          oldStoredState <- storedStateFromFile(file, state.password)
          newStoredState <- StoredState.from(
            state,
            oldStoredState.mnemonic,
            oldStoredState.passphraseDoubleSha256
          )
          _ <- storeStateToFile(
            file,
            newStoredState,
            state.password
          )
        } yield {
          maybeState = Some(state)
        }
      }
    }

    override def revealMnemonic(password: String): Either[SecretStorage.Error, Mnemonic] = {
      revealMnemonicFromFile(file, password)
    }

    private def deriveNextPrivateKey(
        seed: ByteString,
        privateKey: ExtendedPrivateKey
    ): Either[Error, ExtendedPrivateKey] =
      (for {
        index  <- privateKey.path.lastOption.map(_ + 1)
        parent <- BIP32.btcMasterKey(seed).derive(path.init)
        child  <- parent.derive(index)
      } yield child).toRight(CannotDeriveKey)

    private def getState: Either[Error, State] = maybeState.toRight(Locked)
  }

  private def decryptStateFile(
      file: File,
      password: String
  ): Either[Error, ByteString] = {
    Using(Source.fromFile(file)("UTF-8")) { source =>
      val rawFile = source.getLines().mkString
      for {
        secretFile <- Try(read[SecretFile](rawFile)).toEither.left.map(_ => CannotParseFile: Error)
        stateBytes <- AES
          .decrypt(secretFile.toAESEncrytped, password)
          .toEither
          .left
          .map(_ => CannotDecryptSecret)
      } yield stateBytes
    }.toEither.left.map(_ => SecretFileError).flatten
  }

  private def storedStateFromFile(
      file: File,
      password: String
  ): Either[Error, StoredState] = {
    for {
      stateBytes <- decryptStateFile(file, password)
      state      <- deserialize[StoredState](stateBytes).left.map(_ => SecretFileError)
    } yield {
      state
    }
  }

  private def stateFromFile(
      file: File,
      password: String,
      path: AVector[Int],
      mnemonicPassphrase: Option[String]
  ): Either[Error, State] = {
    for {
      state <- storedStateFromFile(file, password)
      passphraseDoubleSha256 = mnemonicPassphrase.map(passphrase =>
        Sha256.doubleHash(ByteString(passphrase))
      )
      _ <-
        if (passphraseDoubleSha256 != state.passphraseDoubleSha256) {
          Left(InvalidMnemonicPassphrase)
        } else {
          Right(())
        }
      seed = state.mnemonic.toSeed(mnemonicPassphrase)
      privateKeys <- deriveKeys(seed, state.numberOfAddresses, path)
      active      <- privateKeys.get(state.activeAddressIndex).toRight(InvalidState)
    } yield {
      State(seed, password, state.isMiner, active, privateKeys)
    }
  }

  private def revealMnemonicFromFile(
      file: File,
      password: String
  ): Either[Error, Mnemonic] = {
    storedStateFromFile(file, password).map(_.mnemonic)
  }

  private def validatePassword(
      file: File,
      password: String
  ): Either[Error, Unit] = decryptStateFile(file, password).map(_ => ())

  private def deriveKeys(
      seed: ByteString,
      number: Int,
      path: AVector[Int]
  ): Either[Error, AVector[ExtendedPrivateKey]] = {
    for {
      _ <- if (number <= 0) Left(InvalidState) else Right(())
      rootPrivateKey <- BIP32
        .btcMasterKey(seed)
        .derive(path.init)
        .toRight(CannotDeriveKey)
      privateKeys <- AVector.from(0 until number).mapE { index =>
        rootPrivateKey.derive(index).toRight(CannotDeriveKey)
      }
    } yield privateKeys
  }

  private def storeStateToFile(
      file: File,
      storedState: StoredState,
      password: String
  ): Either[Error, Unit] = {
    Using
      .Manager { use =>
        val outWriter = use(new PrintWriter(file))
        val encrypted = AES.encrypt(serialize(storedState), password)
        val secretFile =
          SecretFile(encrypted.encrypted, encrypted.salt, encrypted.iv, Constants.walletFileVersion)
        outWriter.write(write(secretFile))
      }
      .toEither
      .left
      .map(_ => SecretFileError)
  }
}
