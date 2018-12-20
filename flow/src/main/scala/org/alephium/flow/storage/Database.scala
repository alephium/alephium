package org.alephium.flow.storage

import akka.util.ByteString
import org.rocksdb.{Options, RocksDB, RocksDBException}

object Database {
  def open(path: String, options: Options): Either[RocksDBException, Database] = {
    execute {
      RocksDB.loadLibrary()
      val db = RocksDB.open(options, path)
      new Database(db)
    }
  }

  def dESTROY(path: String, options: Options): Either[RocksDBException, Unit] = execute {
    RocksDB.destroyDB(path, options)
  }

  @inline
  private def execute[T](f: => T): Either[RocksDBException, T] = {
    executeF(Right(f))
  }

  @inline
  private def executeF[T](f: => Either[RocksDBException, T]): Either[RocksDBException, T] = {
    try f
    catch {
      case e: RocksDBException => Left(e)
    }
  }
}

class Database private (db: RocksDB) {
  import Database.{execute, executeF}

  def close(): Either[RocksDBException, Unit] = execute {
    db.close()
  }

  def getOpt(key: ByteString): Either[RocksDBException, Option[ByteString]] = execute {
    val result = db.get(key.toArray)
    if (result == null) None else Some(ByteString.fromArrayUnsafe(result))
  }

  def get(key: ByteString): Either[RocksDBException, ByteString] = executeF {
    val result = db.get(key.toArray)
    if (result == null) Left(new RocksDBException("key not found"))
    else Right(ByteString.fromArrayUnsafe(result))
  }

  def exists(key: ByteString): Either[RocksDBException, Boolean] = execute {
    val result = db.get(key.toArray)
    result != null
  }

  def put(key: ByteString, value: ByteString): Either[RocksDBException, Unit] = execute {
    db.put(key.toArray, value.toArray)
  }

  def delete(key: ByteString): Either[RocksDBException, Unit] = execute {
    db.delete(key.toArray)
  }
}
