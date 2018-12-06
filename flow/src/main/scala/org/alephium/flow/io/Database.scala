package org.alephium.flow.io

import java.nio.file.Path

import akka.util.ByteString
import org.alephium.crypto.Keccak256
import org.alephium.protocol.model.BlockHeader
import org.alephium.serde._
import org.rocksdb.{Options, RocksDB, RocksDBException}

object Database {
  def open(path: Path, options: Options): IOResult[Database] = execute {
    openUnsafe(path, options)
  }

  def openUnsafe(path: Path, options: Options): Database = {
    RocksDB.loadLibrary()
    val db = RocksDB.open(options, path.toString)
    new Database(path, db)
  }

  def dESTROY(path: Path, options: Options): IOResult[Unit] = execute {
    RocksDB.destroyDB(path.toString, options)
  }

  def dESTROY(db: Database): IOResult[Unit] = execute {
    dESTROYUnsafe(db)
  }

  def dESTROYUnsafe(db: Database): Unit = {
    RocksDB.destroyDB(db.path.toString, new Options())
  }

  @inline
  private def execute[T](f: => T): IOResult[T] = {
    try Right(f)
    catch {
      case e: RocksDBException => Left(RocksDBExpt(e))
    }
  }
}

class Database private (val path: Path, db: RocksDB) {
  import Database._

  def close(): IOResult[Unit] = execute {
    db.close()
  }

  def closeUnsafe(): Unit = db.close()

  def getOpt(key: ByteString): IOResult[Option[ByteString]] = execute {
    getOptUnsafe(key)
  }

  def getOptUnsafe(key: ByteString): Option[ByteString] = {
    val result = db.get(key.toArray)
    if (result == null) None else Some(ByteString.fromArrayUnsafe(result))
  }

  def get(key: ByteString): IOResult[ByteString] = execute {
    getUnsafe(key)
  }

  def getUnsafe(key: ByteString): ByteString = {
    val result = db.get(key.toArray)
    if (result == null) throw RocksDBExpt.keyNotFound.e
    else ByteString.fromArrayUnsafe(result)
  }

  def exists(key: ByteString): IOResult[Boolean] = execute {
    existsUnsafe(key)
  }

  def existsUnsafe(key: ByteString): Boolean = {
    val result = db.get(key.toArray)
    result != null
  }

  def put(key: ByteString, value: ByteString): IOResult[Unit] = execute {
    putUnsafe(key, value)
  }

  def putUnsafe(key: ByteString, value: ByteString): Unit = {
    db.put(key.toArray, value.toArray)
  }

  def delete(key: ByteString): IOResult[Unit] = execute {
    deleteUnsafe(key)
  }

  def deleteUnsafe(key: ByteString): Unit = {
    db.delete(key.toArray)
  }

  def getHeaderOpt(hash: Keccak256): IOResult[Option[BlockHeader]] = execute {
    getHeaderOptUnsafe(hash)
  }

  def getHeaderOptUnsafe(hash: Keccak256): Option[BlockHeader] = {
    val dataOpt = getOptUnsafe(hash.bytes)
    dataOpt.fold[Option[BlockHeader]](None) { data =>
      deserialize[BlockHeader](data) match {
        case Left(e)       => throw e
        case Right(header) => Some(header)
      }
    }
  }

  def getHeader(hash: Keccak256): IOResult[BlockHeader] = execute {
    getHeaderUnsafe(hash)
  }

  def getHeaderUnsafe(hash: Keccak256): BlockHeader = {
    val data = getUnsafe(hash.bytes)
    deserialize[BlockHeader](data) match {
      case Left(e)       => throw e
      case Right(header) => header
    }
  }

  def putHeader(header: BlockHeader): IOResult[Unit] = {
    put(header.hash.bytes, serialize(header))
  }

  def putHeaderUnsafe(header: BlockHeader): Unit = {
    putUnsafe(header.hash.bytes, serialize(header))
  }

  def deleteHeader(hash: Keccak256): IOResult[Unit] = {
    delete(hash.bytes)
  }

  def deleteHeaderUnsafe(hash: Keccak256): Unit = {
    deleteUnsafe(hash.bytes)
  }
}
