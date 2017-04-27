package MSTCP.vegas.more;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Random;
import java.util.Vector;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.logging.Level;
import java.util.logging.Logger;

public class DEPRECATED_NetworkCoder extends Thread {
    final Logger logger;
    
    boolean decode = false; // do we decode packets when able
    Boolean decoding = false; // are we currently decoding
    LinkedBlockingQueue<byte[]> decodedPackets;
    HashMap<Short, MOREPacket> preEncodedPackets; // maps batches to their pre-encoded packet
    
    int flowID = -1;
    
    long fileSize = -1;   // filesize in bytes
    int fileBlocks  = -1; // filesize in blocks
    int fileBatches = -1; // filesize in batches
    
    short nextDecBatch = 0; // next batch to decode
    int nextDecBlock   = 0; // first block of the next batch to decode
    
    Semaphore sem_preEncoded = new Semaphore(1);

    LinkedBlockingQueue<MOREPacket> receivedPackets; // connections add their received packets to this
    HashMap<Short, double[][]> innovChecker = new HashMap<Short, double[][]>(); // maps batches to their innovative checker matrices
    
    HashMap<Short, Vector<MOREPacket>> packetBuffer = new HashMap<Short, Vector<MOREPacket>>(); // maps batches to packets

    Random random = new Random();
    
    public DEPRECATED_NetworkCoder(Logger logger, long fileSize, int flowID, boolean decode) {
        this.logger = logger;
        this.fileSize = fileSize;
        this.fileBlocks = (int) (fileSize / Utils.blockSize + (fileSize % Utils.blockSize > 0 ? 1: 0));
        this.fileBatches = fileBlocks / Utils.batchSize + (fileBlocks % Utils.batchSize > 0 ? 1 : 0);
        this.decode = decode;
        this.flowID = flowID;
        if (decode) { // if requester
            receivedPackets = new LinkedBlockingQueue<MOREPacket>();
            decodedPackets = new LinkedBlockingQueue<byte[]>();
            this.start();
        } else { // if router
            preEncodedPackets = new HashMap<Short, MOREPacket>();
        }
            
    }
    
    public void run() {
        try {
            while(nextDecBatch <= fileBatches)
                isInnovative(receivedPackets.take());
        } catch (InterruptedException e) {
            logger.log(Level.SEVERE, e.getMessage(), e);
            System.exit(1);
        }
    }
    
    public MOREPacket getPreEncodedPacket(final short batch) {
        MOREPacket prevPacket = null;
        
        try {
            sem_preEncoded.acquire();
        } catch (InterruptedException e) {
            logger.log(Level.SEVERE, e.getMessage(), e);
            System.exit(1);
        }
        prevPacket = preEncodedPackets.remove(batch); // get pre-encoded packet
        
        (new Thread() {
            public void run() {
            // pre-encode new packet
                if (packetBuffer.containsKey(batch)) {
                    preEncodedPackets.remove(batch);
                    Vector<MOREPacket> batchBuffer = packetBuffer.get(batch);

                    if (batchBuffer.size() <= 3)
                        for (MOREPacket more: batchBuffer)
                            updatePreEncodedPacket(more);
                    else {
                        // choose 3 packets
                        ArrayList<Integer> pkts = new ArrayList<Integer>(3);
                        int pkt = random.nextInt(batchBuffer.size());
                        for (int i=0; i<3; i++) {
                            pkt = random.nextInt(batchBuffer.size());
                            while (pkts.contains(pkt)) {
                                pkt = random.nextInt(batchBuffer.size());
                            }
                            updatePreEncodedPacket(batchBuffer.get(pkt));
                            
                        }
                    }
                        
                } else {
                    logger.info("No pre-encoded packet for batch " + batch + ".");
                }
                sem_preEncoded.release();
            }
        }).start();
        
        return prevPacket;
    }
    
    
    private synchronized void updatePreEncodedPacket(MOREPacket more) { // add packet to pre-encoded packet
        MOREPacket preEncoded = preEncodedPackets.get(more.getBatch());
        if (preEncoded == null)
            preEncoded = more;
        else {
            int batchSize = Math.min(Utils.batchSize, fileBlocks - more.getBatch() * Utils.batchSize);
            if (batchSize < 0)
                batchSize = fileBlocks;
            MOREPacket prevPkt = preEncodedPackets.get(more.getBatch());
            short baseBlock = (short) (more.getBatch() * Utils.batchSize);
            
            CodeVectorElement[] prevCodeVector = prevPkt.getCodeVector();
            CodeVectorElement[] innovCodeVector = more.getCodeVector();
            CodeVectorElement[] newCodeVector = new CodeVectorElement[batchSize];
            
            short i=0, j=0, k=0;
            short block, coefficient;
            for (i=0; i<batchSize; i++) {
                block = (short) (baseBlock + i);
                coefficient = 0;
                if (prevCodeVector[j].getBlock() == block)
                    coefficient += prevCodeVector[j++].getCoefficient();
                if (innovCodeVector[k].getBlock() == block)
                    coefficient += innovCodeVector[k++].getCoefficient();
                
                newCodeVector[i] = new CodeVectorElement(block, coefficient);
            }
            
            BigInteger encodedData = preEncoded.getEncodedData().add(more.getEncodedData());
            
            preEncoded.setCodeVector(newCodeVector);
            preEncoded.setEncodedData(encodedData);
        }
        
        preEncodedPackets.put(preEncoded.getBatch(), preEncoded);
    }
    
    public boolean isInnovative(MOREPacket more) {
        CodeVectorElement[] codeVector = more.getCodeVector();
        short batch = more.getBatch(); // the batch this packet belongs to
        int baseBlock = batch * Utils.batchSize;
        
        
        if (nextDecBatch <= fileBatches && (!decode || batch > nextDecBatch || (!decoding && batch == nextDecBatch))) { // batch has not already been decoded
            int batchSize = Math.min(Utils.batchSize, fileBlocks - batch * Utils.batchSize);
            if (batchSize < 0)
                batchSize = fileBlocks;
            
            double[][] innovMatrix = innovChecker.get(batch);
            if (innovMatrix == null)
                innovMatrix = new double[batchSize][];
            
            // building coefficients array
            double[] coefficients = new double[batchSize];
            for (CodeVectorElement c: codeVector) {
                coefficients[c.getBlock() - baseBlock] = c.getInnovCoefficient();
            }
            
            for (int i=0; i<batchSize; i++) {
                if (coefficients[i] != 0) { // if u[i] != 0
                    double ui = coefficients[i];
                    if (innovMatrix[i] != null) { // if M[i] exists
                        // u <- u - M[i]u[i]
                        for (int j=0; j<batchSize; j++)
                            coefficients[j] -= innovMatrix[i][j] * ui;
                    } else {
                        logger.info("Received innovative packet for batch " + batch);
                        // admit the modified block into memory
                        Vector<MOREPacket> batchBuffer = packetBuffer.get(batch);
                        if (batchBuffer == null)
                            batchBuffer = new Vector<MOREPacket>();
                        batchBuffer.add(more);
                        packetBuffer.put(batch, batchBuffer);
                        
                        // M[i] <- u/u[i]
                        for (int j=0; j<batchSize; j++)
                            coefficients[j] /= ui;
                        innovMatrix[i] = coefficients;
                        innovChecker.put(batch, innovMatrix);
                        
                        if (decode) {
                            synchronized(decoding) {
                                if (canDecode(batch) && !decoding) {
                                    decoding = true;
                                    (new Decoder()).start();
                                }
                            }
                        } else {
                            // update pre-encoded packet
                            updatePreEncodedPacket(more);
                        }
                        
                        return true;
                    }
                }
            }
        }
        
        logger.info("Received uninnovative packet for batch " + batch);
        return false;
    }
    
    private boolean canDecode(short batch) { // do we have enough DoFs to decode the batch
        int batchSize = Math.min(Utils.batchSize, fileBlocks - batch * Utils.batchSize);
        if (batchSize < 0)
            batchSize = fileBlocks;
        if (batch > fileBatches || packetBuffer.get(batch) == null)
            return false;
        return packetBuffer.get(batch).size() == batchSize;
    }
    
    private class Decoder extends Thread {
        public void run() {
            decode();
        }
        
        public synchronized void decode() {            
            while (canDecode(nextDecBatch)) {
                logger.info("Decoding Batch " + nextDecBatch);
                Vector<MOREPacket> batchBuffer = packetBuffer.get(nextDecBatch);
                int batchSize = Math.min(Utils.batchSize, fileBlocks - nextDecBatch * Utils.batchSize);
                if (batchSize < 0)
                    batchSize = fileBlocks;
                int baseBlock = nextDecBatch * Utils.batchSize;
                
                // set up coding matrix
                BigDecimal[][] codingMatrix = new BigDecimal[batchSize][batchSize];
                BigDecimal[] codedData = new BigDecimal[batchSize];
                
                for (int i=0; i<batchSize; i++) {
                    CodeVectorElement[] codeVector = batchBuffer.get(i).getCodeVector();
                    int k = 0;
                    for (int j=0; j<batchSize; j++) {
                        codingMatrix[i][j] = new BigDecimal(
                                codeVector[k].getBlock() == baseBlock + j ? codeVector[k++].getCoefficient() : 0
                                );
                    }
                    codedData[i] = new BigDecimal(batchBuffer.get(i).getEncodedData());
                }
                
                // get the inverse
                BigDecimal[][] inverseCodingMatrix = invert(codingMatrix);
                BigDecimal block;
                
                // multiple coded data by inverse matrix to recover packets
                try {
                    for (int i=0; i<batchSize; i++) {
                        block = new BigDecimal(0);
                        for (int j=0; j<batchSize; j++) {
                            BigDecimal inverseCoefficient = inverseCodingMatrix[i][j];
                            BigDecimal codedBlock = codedData[j];
                            block = block.add(codedBlock.multiply(inverseCoefficient));
                        }
                        BigInteger data = block.setScale(0, RoundingMode.HALF_UP).toBigInteger();
                        // byte[] dataBytes = data.toByteArray();
                        decodedPackets.put(data.toByteArray());
                    }
                } catch (InterruptedException e) {
                    logger.log(Level.SEVERE, e.getMessage(), e);
                    System.exit(1);
                }
                logger.info("Decoded Batch " + nextDecBatch);
                
                innovChecker.remove(nextDecBatch);
                packetBuffer.remove(nextDecBatch);
                
                nextDecBatch++;
                nextDecBlock+= batchSize;
            }
            
            synchronized(decoding) {
                decoding = false;
            }
        }
    }

    private static BigDecimal[][] invert(BigDecimal a[][]) {
        int n = a.length;
        BigDecimal x[][] = new BigDecimal[n][n];
        BigDecimal b[][] = new BigDecimal[n][n];
        int index[] = new int[n];
        for (int i = 0; i < n; i++)
            for (int j = 0; j < n; j++)
                b[i][j] = new BigDecimal(i == j ? 1 : 0);
        // Transform the matrix into an upper triangle
        gaussian(a, index);
        // Update the matrix b[i][j] with the ratios stored
        for (int i = 0; i < n - 1; ++i)
            for (int j = i + 1; j < n; ++j)
                for (int k = 0; k < n; ++k)
                    b[index[j]][k] = b[index[j]][k].subtract(a[index[j]][i].multiply(b[index[i]][k]));
        // Perform backward substitutions
        for (int i = 0; i < n; ++i) {
            x[n - 1][i] = b[index[n - 1]][i].divide(a[index[n - 1]][n - 1], Utils.precision, RoundingMode.HALF_UP);
            for (int j = n - 2; j >= 0; --j) {
                x[j][i] = b[index[j]][i];
                for (int k = j + 1; k < n; ++k) {
                    x[j][i] = x[j][i].subtract(a[index[j]][k].multiply(x[k][i]));
                }
                x[j][i] = x[j][i].divide(a[index[j]][j], Utils.precision, RoundingMode.HALF_UP);
            }
        }
        return x;
    }

    // Method to carry out the partial-pivoting Gaussian
    // elimination. Here index[] stores pivoting order.
    public static void gaussian(BigDecimal a[][], int index[]) {
        int n = index.length;
        BigDecimal c[] = new BigDecimal[n];
        // Initialize the index
        for (int i = 0; i < n; ++i)
            index[i] = i;
        // Find the rescaling factors, one from each row
        for (int i = 0; i < n; ++i) {
            BigDecimal c1 = new BigDecimal("0");
            for (int j = 0; j < n; ++j) {
                BigDecimal c0 = (a[i][j]).abs();
                if (c0.compareTo(c1) > 0)
                    c1 = c0;
            }
            c[i] = c1;
        }
        // Search the pivoting element from each column
        int k = 0;
        for (int j = 0; j < n - 1; ++j) {
            BigDecimal pi1 = new BigDecimal("0");
            for (int i = j; i < n; ++i) {
                BigDecimal pi0 = a[index[i]][j].abs();
                pi0 = pi0.divide(c[index[i]], Utils.precision, RoundingMode.HALF_UP);
                if (pi0.compareTo(pi1) > 0) {
                    pi1 = pi0;
                    k = i;
                }
            }
            // Interchange rows according to the pivoting order
            int itmp = index[j];
            index[j] = index[k];
            index[k] = itmp;
            for (int i = j + 1; i < n; ++i) {
                BigDecimal pj = a[index[i]][j].divide(a[index[j]][j], Utils.precision, RoundingMode.HALF_UP);
                // Record pivoting ratios below the diagonal
                a[index[i]][j] = pj;
                // Modify other elements accordingly
                for (int l = j + 1; l < n; ++l)
                    a[index[i]][l] = a[index[i]][l].subtract(pj.multiply(a[index[j]][l]));
            }
        }
    }
    
    public short nextCoefficient(int batchSize) {
        double prob = batchSize < 8 ? 1 : (5.0 / batchSize); // probability any given block is included TODO: find best parameters
        return (short) ( random.nextDouble() < prob ? random.nextInt( (2 * Byte.MAX_VALUE) + 1) - Byte.MAX_VALUE : 0);
    }
}
