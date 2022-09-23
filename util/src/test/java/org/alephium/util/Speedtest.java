/**
 * Diff Speed Test
 *
 * Compile from diff-match-patch/java with:
 * javac -d classes src/name/fraser/neil/plaintext/DiffMatchPatch.java tests/name/fraser/neil/plaintext/Speedtest.java
 * Execute with:
 * java -classpath classes name/fraser/neil/plaintext/Speedtest
 *
 * @author fraser@google.com (Neil Fraser)
 */

package org.alephium.util;

import java.io.*;

public class Speedtest {

    public static void main(String args[]) throws IOException {
        String text1 = readFile("Speedtest1.txt");
        String text2 = readFile("Speedtest2.txt");

        DiffMatchPatch dmp = new DiffMatchPatch();
        dmp.Diff_Timeout = 0;

        // Execute one reverse diff as a warmup.
        dmp.diff_main(text2, text1, false);

        long start_time = System.nanoTime();
        dmp.diff_main(text1, text2, false);
        long end_time = System.nanoTime();
        System.out.printf("Elapsed time: %f\n", ((end_time - start_time) / 1000000000.0));
    }

    private static String readFile(String fileName) throws IOException {
        // Read a file from disk and return the text contents.
        System.out.println(Speedtest.class.getResource(Speedtest.class.getSimpleName() + ".class"));
        System.out.println(Speedtest.class.getResource(fileName));
        InputStream is = Speedtest.class.getResourceAsStream(fileName);
        StringBuilder sb = new StringBuilder();
        BufferedReader bufRead = new BufferedReader(new InputStreamReader(is));
        try {
            String line = bufRead.readLine();
            while (line != null) {
                sb.append(line).append('\n');
                line = bufRead.readLine();
            }
        } finally {
            bufRead.close();
            is.close();
        }
        return sb.toString();
    }
}
