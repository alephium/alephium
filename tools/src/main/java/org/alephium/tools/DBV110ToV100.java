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

package org.alephium.tools;

import java.lang.System;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.stream.Collectors;

import org.rocksdb.*;

// downgrade database from 1.1.0 to 1.0.0
// 1. remove `broker` column family
// 2. change database version
public class DBV110ToV100 {
    public static byte[] hexToByte(String hex){
        byte[] bytes = new byte[hex.length() / 2];
        for (int i = 0; i < (hex.length() / 2); i++) {
            int index = i * 2;
            int num = Integer.parseInt(hex.substring(index, index + 2), 16);
            bytes[i] = (byte)num;
        }
        return bytes;
    }

    public static boolean contains(List<byte[]> list, byte[] elem) {
        for (byte[] bytes : list) {
            if (Arrays.equals(bytes, elem)) {
                return true;
            }
        }
        return false;
    }

    public static void main(String[] args) {
        String homePath = System.getProperty("user.home");
        String dbPath = homePath + "/.alephium/mainnet/db";

        try {
            byte[] brokerNameBytes = "broker".getBytes(StandardCharsets.UTF_8);
            byte[] allNameBytes = "all".getBytes(StandardCharsets.UTF_8);
            List<byte[]> cfsName = RocksDB.listColumnFamilies(new Options(), dbPath);
            if (!contains(cfsName, brokerNameBytes)) {
                return;
            }

            List<ColumnFamilyDescriptor> descriptors = cfsName
                    .stream()
                    .map(cf -> {
                        var cfOptions = new ColumnFamilyOptions();
                        return new ColumnFamilyDescriptor(cf, cfOptions);
                    })
                    .collect(Collectors.toList());

            List<ColumnFamilyHandle> cfHandlers = new LinkedList<>();
            RocksDB db = RocksDB.open(dbPath, descriptors, cfHandlers);
            ColumnFamilyHandle allCfHandler = null, brokerCfHandler = null;

            for (ColumnFamilyHandle handler : cfHandlers) {
                if (Arrays.equals(handler.getName(), brokerNameBytes)) {
                    brokerCfHandler = handler;
                    continue;
                }

                if (Arrays.equals(handler.getName(), allNameBytes)) {
                    allCfHandler = handler;
                }
            }

            byte[] dbVersionKey = hexToByte("95242e8e245f7836e01cf61f5a4ac3e2ff2f756d7db11128c9002c224f6410b405");
            byte[] dbVersion100 = hexToByte("80010000");
            db.dropColumnFamily(brokerCfHandler);
            byte[] dbVersion = db.get(allCfHandler, dbVersionKey);
            if (!Arrays.equals(dbVersion, dbVersion100)) {
                db.put(allCfHandler, dbVersionKey, dbVersion100);
            }

        } catch (RocksDBException exception) {
            System.out.println("Downgrade database error: " + exception);
        }
    }
}
