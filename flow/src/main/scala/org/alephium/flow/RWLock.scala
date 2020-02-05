package org.alephium.flow

import java.util.concurrent.locks.ReentrantReadWriteLock

trait RWLock {
  val lock      = new ReentrantReadWriteLock()
  val readLock  = lock.readLock()
  val writeLock = lock.writeLock()

  // Note: functions started with _ are for testing
  def _getLock: ReentrantReadWriteLock = lock

  def readOnly[T](f: => T): T = {
    readLock.lock()
    try {
      f
    } finally {
      readLock.unlock()
    }
  }

  def writeOnly[T](f: => T): T = {
    writeLock.lock()
    try {
      f
    } finally {
      writeLock.unlock()
    }
  }
}
