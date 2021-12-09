# python3.5+
# pip3 install python-rocksdb

# downgrade database from 1.1.0 to 1.0.0
# 1. remove `broker` column family
# 2. change database version

# python3 ./db_v1.1.0_to_v1.0.0.py

from pathlib import Path
from hashlib import blake2b
import rocksdb
import sys

dbVersionPostfix = bytes.fromhex('05')
dbVersion100 = bytes.fromhex('80010000')
dbVersion110 = bytes.fromhex('80010100')

homePath = Path.home()
dbPath = Path.home().joinpath('.alephium', 'mainnet', 'db')

def columnFamilyExist(name):
    cfList = rocksdb.list_column_families(str(dbPath), rocksdb.Options())
    return name in cfList

def changeDBVersion(db, cfHandler, newVersion):
    h = blake2b(digest_size = 32)
    h.update(b'databaseVersion')
    key = bytes.fromhex(h.hexdigest()) + dbVersionPostfix
    oldVersion = db.get((cfHandler, key))
    if oldVersion == newVersion:
        return
    else:
        db.put((cfHandler, key), newVersion)

if not columnFamilyExist(b'broker'):
    sys.exit()

cfs = {
    b'default':   rocksdb.ColumnFamilyOptions(),
    b'trie':      rocksdb.ColumnFamilyOptions(),
    b'readytx':   rocksdb.ColumnFamilyOptions(),
    b'pendingtx': rocksdb.ColumnFamilyOptions(),
    b'header':    rocksdb.ColumnFamilyOptions(),
    b'block':     rocksdb.ColumnFamilyOptions(),
    b'all':       rocksdb.ColumnFamilyOptions(),
    b'broker':    rocksdb.ColumnFamilyOptions(),
}

db = rocksdb.DB(str(dbPath), rocksdb.Options(), cfs)
brokerCfHandler = db.column_families[list(cfs.keys()).index(b'broker')]
db.drop_column_family(brokerCfHandler)

allCfHandler = db.column_families[list(cfs.keys()).index(b'all')]
changeDBVersion(db, allCfHandler, dbVersion100)
# db.close()

