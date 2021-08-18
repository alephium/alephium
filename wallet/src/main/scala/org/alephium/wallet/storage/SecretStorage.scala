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
import org.alephium.crypto.AES
import org.alephium.crypto.wallet.{BIP32, Mnemonic}
import org.alephium.crypto.wallet.BIP32.ExtendedPrivateKey
import org.alephium.json.Json._
import org.alephium.serde.{deserialize, serialize, Serde}
import org.alephium.util.AVector
import org.alephium.wallet.Constants

trait SecretStorage {
  def lock(): Unit
  def unlock(password: String): Either[SecretStorage.Error, Unit]
  def delete(password: String): Either[SecretStorage.Error, Unit]
  def isLocked(): Boolean
  def isMiner(): Either[SecretStorage.Error, Boolean]
  def getCurrentPrivateKey(): Either[SecretStorage.Error, ExtendedPrivateKey]
  def getAllPrivateKeys()
      : Either[SecretStorage.Error, (ExtendedPrivateKey, AVector[ExtendedPrivateKey])]
  def deriveNextKey(): Either[SecretStorage.Error, ExtendedPrivateKey]
  def changeActiveKey(key: ExtendedPrivateKey): Either[SecretStorage.Error, Unit]
  def getMnemonic(password: String): Either[SecretStorage.Error, Mnemonic]
}

object SecretStorage {

  sealed trait Error

  case object Locked                  extends Error
  case object CannotDeriveKey         extends Error
  case object CannotParseFile         extends Error
  case object CannotDecryptSecret     extends Error
  case object InvalidState            extends Error
  case object SecretFileError         extends Error
  case object SecretFileAlreadyExists extends Error
  case object UnknownKey              extends Error

  final case class SecretFileNotFound(file: File) extends Error

  final case class StoredState(
      seed: ByteString,
      isMiner: Boolean,
      numberOfAddresses: Int,
      activeAddressIndex: Int,
      mnemonic: String
  )

  object StoredState {
    implicit val serde: Serde[StoredState] =
      Serde.forProduct5(
        apply,
        state =>
          (
            state.seed,
            state.isMiner,
            state.numberOfAddresses,
            state.activeAddressIndex,
            state.mnemonic
          )
      )

    def from(state: State): Either[Error, StoredState] = {
      val index = state.privateKeys.indexWhere(_.privateKey == state.activeKey.privateKey)
      for {
        _ <- Either.cond(index >= 0, (), UnknownKey)
      } yield {
        StoredState(
          state.seed,
          state.isMiner,
          state.privateKeys.length,
          index,
          state.mnemonic.toLongString
        )
      }
    }
  }

  final private case class State(
      seed: ByteString,
      password: String,
      isMiner: Boolean,
      activeKey: ExtendedPrivateKey,
      privateKeys: AVector[ExtendedPrivateKey],
      mnemonic: Mnemonic
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
      seed: ByteString,
      password: String,
      isMiner: Boolean,
      file: File,
      path: AVector[Int],
      mnemonic: Mnemonic
  ): Either[Error, SecretStorage] = {
    if (file.exists) {
      Left(SecretFileAlreadyExists)
    } else {
      for {
        _ <- storeStateToFile(
          file,
          StoredState(
            seed,
            isMiner,
            numberOfAddresses = 1,
            activeAddressIndex = 0,
            mnemonic.toLongString
          ),
          password
        )
        state <- stateFromFile(file, password, path)
      } yield {
        new Impl(file, Some(state), path)
      }
    }
  }

  private[storage] def fromFile(
      file: File,
      password: String,
      path: AVector[Int]
  ): Either[Error, SecretStorage] = {
    for {
      state <- stateFromFile(file, password, path)
    } yield {
      new Impl(file, Some(state), path)
    }
  }

  //TODO add some `synchronized` for the state
  private class Impl(file: File, initialState: Option[State], path: AVector[Int])
      extends SecretStorage {

    private var maybeState: Option[State] = initialState

    override def lock(): Unit = {
      if (!isLocked()) {
        maybeState = None
      }
    }

    override def unlock(password: String): Either[Error, Unit] = {
      for {
        state <- stateFromFile(file, password, path)
      } yield {
        maybeState = Some(state)
      }
    }

    override def delete(password: String): Either[Error, Unit] = {
      for {
        _   <- stateFromFile(file, password, path)
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
          storedState <- StoredState.from(state)
          _ <- storeStateToFile(
            file,
            storedState,
            state.password
          )
        } yield {
          maybeState = Some(state)
        }
      }
    }

    override def getMnemonic(password: String): Either[SecretStorage.Error, Mnemonic] = {
      for {
        state <- stateFromFile(file, password, path)
      } yield {
        state.mnemonic
      }
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

  private def stateFromFile(
      file: File,
      password: String,
      path: AVector[Int]
  ): Either[Error, State] = {
    Using(Source.fromFile(file)("UTF-8")) { source =>
      val rawFile = source.getLines().mkString
      for {
        secretFile <- Try(read[SecretFile](rawFile)).toEither.left.map(_ => CannotParseFile)
        stateBytes <- AES
          .decrypt(secretFile.toAESEncrytped, password)
          .toEither
          .left
          .map(_ => CannotDecryptSecret)
        state       <- deserialize[StoredState](stateBytes).left.map(_ => SecretFileError)
        mnemonic    <- Mnemonic.from(state.mnemonic).toRight(InvalidState)
        privateKeys <- deriveKeys(state.seed, state.numberOfAddresses, path)
        active      <- privateKeys.get(state.activeAddressIndex).toRight(InvalidState)
      } yield {
        State(state.seed, password, state.isMiner, active, privateKeys, mnemonic)
      }
    }.toEither.left.map(_ => SecretFileError).flatten
  }

  private def deriveKeys(
      seed: ByteString,
      number: Int,
      path: AVector[Int]
  ): Either[Error, AVector[ExtendedPrivateKey]] = {
    if (number <= 0) {
      Right(AVector.empty)
    } else {
      for {
        rootPrivateKey <- BIP32
          .btcMasterKey(seed)
          .derive(path.init)
          .toRight(CannotDeriveKey)
        privateKeys <- AVector.from(0 until number).mapE { index =>
          rootPrivateKey.derive(index).toRight(CannotDeriveKey)
        }
      } yield privateKeys
    }
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
