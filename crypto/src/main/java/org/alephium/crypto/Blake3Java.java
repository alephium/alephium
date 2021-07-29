package org.alephium.crypto;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * We forked this from https://github.com/rctcwyvrn/blake3 to support old JDK versions
 *
 * Translation of the Blake3 reference implementation from Rust to Java
 * BLAKE3 Source: https://github.com/BLAKE3-team/BLAKE3
 * Translator: rctcwyvrn
 */
public class Blake3Java {
    private static final char[] HEX_ARRAY = "0123456789abcdef".toCharArray();

    private static final int DEFAULT_HASH_LEN = 32;
    private static final int OUT_LEN = 32;
    private static final int KEY_LEN = 32;
    private static final int BLOCK_LEN = 64;
    private static final int CHUNK_LEN = 1024;

    private static final int CHUNK_START = 1;
    private static final int CHUNK_END = 2;
    private static final int PARENT = 4;
    private static final int ROOT = 8;
    private static final int KEYED_HASH = 16;
    private static final int DERIVE_KEY_CONTEXT = 32;
    private static final int DERIVE_KEY_MATERIAL = 64;

    private static final int[] IV = {
            0x6A09E667, 0xBB67AE85, 0x3C6EF372, 0xA54FF53A, 0x510E527F, 0x9B05688C, 0x1F83D9AB, 0x5BE0CD19
    };

    private static final int[] MSG_PERMUTATION = {
            2, 6, 3, 10, 7, 0, 4, 13, 1, 11, 12, 5, 9, 14, 15, 8
    };

    private static int wrappingAdd(int a, int b){
        return (a + b);
    }

    private static int rotateRight(int x, int len){
        return (x >>> len) | (x << (32 - len));
    }

    private static void g(int[] state, int a, int b, int c, int d, int mx, int my){
        state[a] = wrappingAdd(wrappingAdd(state[a], state[b]), mx);
        state[d] = rotateRight((state[d] ^ state[a]), 16);
        state[c] = wrappingAdd(state[c], state[d]);
        state[b] = rotateRight((state[b] ^ state[c]), 12);
        state[a] = wrappingAdd(wrappingAdd(state[a], state[b]), my);
        state[d] = rotateRight((state[d] ^ state[a]), 8);
        state[c] = wrappingAdd(state[c], state[d]);
        state[b] = rotateRight((state[b] ^ state[c]), 7);
    }

    private static void roundFn(int[] state, int[] m){
        // Mix columns
        g(state,0,4,8,12,m[0],m[1]);
        g(state,1,5,9,13,m[2],m[3]);
        g(state,2,6,10,14,m[4],m[5]);
        g(state,3,7,11,15,m[6],m[7]);

        // Mix diagonals
        g(state,0,5,10,15,m[8],m[9]);
        g(state,1,6,11,12,m[10],m[11]);
        g(state,2,7,8,13,m[12],m[13]);
        g(state,3,4,9,14,m[14],m[15]);
    }

    private static int[] permute(int[] m){
        int[] permuted = new int[16];
        for(int i = 0;i<16;i++){
            permuted[i] = m[MSG_PERMUTATION[i]];
        }
        return permuted;
    }

    private static void show(String prefix, int[] state, int[] blockWords) {
        System.out.println(prefix + " state: " + Arrays.toString(state));
        System.out.println(prefix + " blockWords " + Arrays.toString(blockWords));
        System.out.println();
    }

    private static int[] compress(int[] chainingValue, int[] blockWords, long counter, int blockLen, int flags){
        int counterInt = (int) (counter & 0xffffffffL);
        int counterShift = (int) ((counter >> 32) & 0xffffffffL);
        int[] state = {
                chainingValue[0],
                chainingValue[1],
                chainingValue[2],
                chainingValue[3],
                chainingValue[4],
                chainingValue[5],
                chainingValue[6],
                chainingValue[7],
                IV[0],
                IV[1],
                IV[2],
                IV[3],
                counterInt,
                counterShift,
                blockLen,
                flags
        };
        show("== initial", state, blockWords);
        roundFn(state, blockWords);         // Round 1
        blockWords = permute(blockWords);
        show("== round1", state, blockWords);
        roundFn(state, blockWords);         // Round 2
        blockWords = permute(blockWords);
        show("== round2", state, blockWords);
        roundFn(state, blockWords);         // Round 3
        blockWords = permute(blockWords);
        show("== round3", state, blockWords);
        roundFn(state, blockWords);         // Round 4
        blockWords = permute(blockWords);
        show("== round4", state, blockWords);
        roundFn(state, blockWords);         // Round 5
        blockWords = permute(blockWords);
        show("== round5", state, blockWords);
        roundFn(state, blockWords);         // Round 6
        blockWords = permute(blockWords);
        show("== round6", state, blockWords);
        roundFn(state, blockWords);         // Round 7
        show("== round7", state, blockWords);

        for(int i = 0; i<8; i++){
            state[i] ^= state[i+8];
            state[i+8] ^= chainingValue[i];
        }
        show("== final", state, blockWords);
        return state;
    }

    private static int[] wordsFromLEBytes(byte[] bytes){
        int[] words = new int[bytes.length/4];
        ByteBuffer buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);

        for(int i=0; i<words.length; i++){
            words[i] = buf.getInt();
        }
        return words;
    }

    // Node of the Blake3 hash tree
    // Is either chained into the next node using chainingValue()
    // Or used to calculate the hash digest using rootOutputBytes()
    private static class Node {
        int[] inputChainingValue;
        int[] blockWords;
        long counter;
        int blockLen;
        int flags;

        @Override
        public String toString() {
            return "Node{" +
                    "inputChainingValue=" + Arrays.toString(inputChainingValue) +
                    ", blockWords=" + Arrays.toString(blockWords) +
                    ", counter=" + counter +
                    ", blockLen=" + blockLen +
                    ", flags=" + flags +
                    '}';
        }

        private Node(int[] inputChainingValue, int[] blockWords, long counter, int blockLen, int flags) {
            this.inputChainingValue = inputChainingValue;
            this.blockWords = blockWords;
            this.counter = counter;
            this.blockLen = blockLen;
            this.flags = flags;
        }

        // Return the 8 int CV
        private int[] chainingValue(){
            return Arrays.copyOfRange(
                    compress(inputChainingValue, blockWords, counter, blockLen, flags),
                    0,8);
        }

        private byte[] rootOutputBytes(int outLen){
            System.out.println("========== initial node state ============");
            System.out.println(this);
            System.out.println();
            int outputCounter = 0;
            int outputsNeeded = Math.floorDiv(outLen,(2*OUT_LEN)) + 1;
            byte[] hash = new byte[outLen];
            int i = 0;
            while(outputCounter < outputsNeeded){
                int[] words = compress(inputChainingValue, blockWords, outputCounter, blockLen,flags | ROOT );
                System.out.println("========== node state " + outputCounter + " ============");
                System.out.println(this);
                System.out.println();

                for(int word: words){
                    for(byte b: ByteBuffer.allocate(4)
                            .order(ByteOrder.LITTLE_ENDIAN)
                            .putInt(word)
                            .array()){
                        hash[i] = b;
                        i+=1;
                        if(i == outLen){
                            return hash;
                        }
                    }
                }
                outputCounter+=1;
            }
            throw new IllegalStateException("Uh oh something has gone horribly wrong. Please create an issue on https://github.com/rctcwyvrn/blake3");
        }
    }

    // Helper object for creating new Nodes and chaining them
    private static class ChunkState {
        int[] chainingValue;
        long chunkCounter;
        byte[] block = new byte[BLOCK_LEN];
        byte blockLen = 0;
        byte blocksCompressed = 0;
        int flags;

        @Override
        public String toString() {
            return "ChunkState{" +
                    "chainingValue=" + Arrays.toString(chainingValue) +
                    ", chunkCounter=" + chunkCounter +
                    ", block=" + Arrays.toString(block) +
                    ", blockLen=" + blockLen +
                    ", blocksCompressed=" + blocksCompressed +
                    ", flags=" + flags +
                    '}';
        }

        public ChunkState(int[] key, long chunkCounter, int flags){
            this.chainingValue = key;
            this.chunkCounter = chunkCounter;
            this.flags = flags;
        }

        public int len(){
            return BLOCK_LEN * blocksCompressed + blockLen;
        }

        private int startFlag(){
            return blocksCompressed == 0? CHUNK_START: 0;
        }

        private void update(byte[] input) {
            int currPos = 0;
            while (currPos < input.length) {

                // Chain the next 64 byte block into this chunk/node
                if (blockLen == BLOCK_LEN) {
                    int[] blockWords = wordsFromLEBytes(block);
                    this.chainingValue = Arrays.copyOfRange(
                            compress(this.chainingValue, blockWords, this.chunkCounter, BLOCK_LEN,this.flags | this.startFlag()),
                            0, 8);
                    blocksCompressed += 1;
                    this.block = new byte[BLOCK_LEN];
                    this.blockLen = 0;
                }

                // Take bytes out of the input and update
                int want = BLOCK_LEN - this.blockLen; // How many bytes we need to fill up the current block
                int canTake = Math.min(want, input.length - currPos);

                System.arraycopy(input, currPos, block, blockLen, canTake);
                blockLen += canTake;
                currPos+=canTake;
            }
        }

        private Node createNode(){
            return new Node(chainingValue, wordsFromLEBytes(block), chunkCounter, blockLen, flags | startFlag() | CHUNK_END);
        }
    }

    // Hasher
    private ChunkState chunkState;
    private int[] key;
    private final int[][] cvStack = new int[54][];
    private byte cvStackLen = 0;
    private int flags;

    private Blake3Java(){
        initialize(IV,0);
    }

    private Blake3Java(byte[] key){
        initialize(wordsFromLEBytes(key), KEYED_HASH);
    }


    private Blake3Java(String context){
        Blake3Java contextHasher = new Blake3Java();
        contextHasher.initialize(IV, DERIVE_KEY_CONTEXT);
        contextHasher.update(context.getBytes(StandardCharsets.UTF_8));
        int[] contextKey = wordsFromLEBytes(contextHasher.digest());
        initialize(contextKey, DERIVE_KEY_MATERIAL);
    }

    private void initialize(int[] key, int flags){
        this.chunkState = new ChunkState(key, 0, flags);
        this.key = key;
        this.flags = flags;
    }

    /**
     * Append the byte contents of the file to the hash tree
     * @param file File to be added
     * @throws java.io.IOException If the file does not exist
     */
    public void update(File file) throws IOException {
        // Update the hasher 4kb at a time to avoid memory issues when hashing large files
        try(InputStream ios = new FileInputStream(file)){
            byte[] buffer = new byte[4096];
            int read = 0;
            while((read = ios.read(buffer)) != -1){
                if(read == buffer.length) {
                    update(buffer);
                } else {
                    update(Arrays.copyOfRange(buffer, 0, read));
                }
            }
        }
    }

    /**
     * Appends new data to the hash tree
     * @param input Data to be added
     */
    public void update(byte[] input){
        System.out.println("========== initial chunk state ============");
        System.out.println(this.chunkState.toString());
        System.out.println();

        int currPos = 0;
        while(currPos < input.length) {

            // If this chunk has chained in 16 64 bytes of input, add its CV to the stack
            if (chunkState.len() == CHUNK_LEN) {
                int[] chunkCV = chunkState.createNode().chainingValue();
                long totalChunks = chunkState.chunkCounter + 1;
                addChunkChainingValue(chunkCV, totalChunks);
                chunkState = new ChunkState(key, totalChunks, flags);
            }

            int want = CHUNK_LEN - chunkState.len();
            int take = Math.min(want, input.length - currPos);
            chunkState.update(Arrays.copyOfRange(input, currPos, currPos + take));
            currPos+=take;

            System.out.println("========== chunk state " + currPos + " ============");
            System.out.println(this.chunkState.toString());
            System.out.println();
        }
    }

    /**
     * Generate the blake3 hash for the current tree with the given byte length
     * @param hashLen The number of bytes of hash to return
     * @return The byte array representing the hash
     */
    public byte[] digest(int hashLen){
        Node node = this.chunkState.createNode();
        int parentNodesRemaining = cvStackLen;
        while(parentNodesRemaining > 0){
            parentNodesRemaining -=1;
            node = parentNode(
                    cvStack[parentNodesRemaining],
                    node.chainingValue(),
                    key,
                    flags
            );
        }
        return node.rootOutputBytes(hashLen);
    }

    /**
     * Generate the blake3 hash for the current tree with the default byte length of 32
     * @return The byte array representing the hash
     */
    public byte[] digest(){
        return digest(DEFAULT_HASH_LEN);
    }

    /**
     * Generate the blake3 hash for the current tree with the given byte length
     * @param hashLen The number of bytes of hash to return
     * @return The hex string representing the hash
     */
    public String hexdigest(int hashLen){
        return bytesToHex(digest(hashLen));
    }

    /**
     * Generate the blake3 hash for the current tree with the default byte length of 32
     * @return The hex string representing the hash
     */
    public String hexdigest(){
        return hexdigest(DEFAULT_HASH_LEN);
    }

    private void pushStack(int[] cv){
        this.cvStack[this.cvStackLen] = cv;
        cvStackLen+=1;
    }

    private int[] popStack(){
        this.cvStackLen-=1;
        return cvStack[cvStackLen];
    }

    // Combines the chaining values of two children to create the parent node
    private static Node parentNode(int[] leftChildCV, int[] rightChildCV, int[] key, int flags){
        int[] blockWords = new int[16];
        int i = 0;
        for(int x: leftChildCV){
            blockWords[i] = x;
            i+=1;
        }
        for(int x: rightChildCV){
            blockWords[i] = x;
            i+=1;
        }
        return new Node(key, blockWords, 0, BLOCK_LEN, PARENT | flags);
    }

    private static int[] parentCV(int[] leftChildCV, int[] rightChildCV, int[] key, int flags){
        return parentNode(leftChildCV, rightChildCV, key, flags).chainingValue();
    }

    private void addChunkChainingValue(int[] newCV, long totalChunks){
        while((totalChunks & 1) == 0){
            newCV = parentCV(popStack(), newCV, key, flags);
            totalChunks >>=1;
        }
        pushStack(newCV);
    }

    private static String bytesToHex(byte[] bytes) {
        char[] hexChars = new char[bytes.length * 2];
        for (int j = 0; j < bytes.length; j++) {
            int v = bytes[j] & 0xFF;
            hexChars[j * 2] = HEX_ARRAY[v >>> 4];
            hexChars[j * 2 + 1] = HEX_ARRAY[v & 0x0F];
        }
        return new String(hexChars);
    }

    /**
     * Construct a BLAKE3 blake3 hasher
     */
    public static Blake3Java newInstance(){
        return new Blake3Java();
    }

    /**
     * Construct a new BLAKE3 keyed mode hasher
     * @param key The 32 byte key
     * @throws IllegalStateException If the key is not 32 bytes
     */
    public static Blake3Java newKeyedHasher(byte[] key){
        if(!(key.length == KEY_LEN)) throw new IllegalStateException("Invalid key length");
        return new Blake3Java(key);
    }

    /**
     * Construct a new BLAKE3 key derivation mode hasher
     * The context string should be hardcoded, globally unique, and application-specific. <br><br>
     * A good default format is <i>"[application] [commit timestamp] [purpose]"</i>, <br>
     * eg "example.com 2019-12-25 16:18:03 session tokens v1"
     * @param context Context string used to derive keys.
     */
    public static Blake3Java newKeyDerivationHasher(String context){
        return new Blake3Java(context);
    }
}