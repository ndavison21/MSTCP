package MSTCP.vegas.more;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.Random;
import java.util.Vector;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.logging.Level;
import java.util.logging.Logger;

public class NetworkCoder extends Thread {
    final Logger logger;
    
    boolean decode = false; // do we decode packets when able
    boolean decoding = false; // are we currently decoding
    LinkedBlockingQueue<byte[]> decodedPackets;
    
    long fileSize = -1;   // filesize in bytes
    int fileBlocks  = -1; // filesize in blocks
    int fileBatches = -1; // filesize in batches
    
    int nextDecBatch = 0; // next batch to decode
    int nextDecBlock = 0; // first block of the next batch to decode

    LinkedBlockingQueue<MOREPacket> receivedPackets = new LinkedBlockingQueue<MOREPacket>(); // connections add their received packets to this
    // maps batches to their innovative checker matrices
    HashMap<Integer, double[][]> innovChecker = new HashMap<Integer, double[][]>(); 
    // maps batches to packets
    HashMap<Integer, Vector<MOREPacket>> packetBuffer = new HashMap<Integer, Vector<MOREPacket>>();

    Random random = new Random();
    
    public NetworkCoder(Logger logger, long fileSize, boolean decode) {
        this.logger = logger;
        this.fileSize = fileSize;
        this.fileBlocks = (int) (fileSize / Utils.blockSize + (fileSize % Utils.blockSize > 0 ? 1: 0));
        this.fileBatches = fileBlocks / Utils.batchSize + (fileBlocks % Utils.batchSize > 0 ? 1 : 0);
        this.decode = decode;
        if (decode) {
            decodedPackets = new LinkedBlockingQueue<byte[]>();
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
    
    public synchronized boolean isInnovative(MOREPacket more) {
        CodeVectorElement[] codeVector = more.getCodeVector();
        int batch = codeVector[0].getBlock() / Utils.batchSize; // the batch this packet belongs to
        int baseBlock = batch * Utils.batchSize;
        
        if (nextDecBatch <= fileBatches && (batch > nextDecBatch || (!decoding && batch == nextDecBatch))) { // batch has not already been decoded
            int batchSize = Math.min(Utils.batchSize, fileBlocks - batch * Utils.batchSize);
            if (batchSize < 0)
                batchSize = fileBlocks;
            
            double[][] innovMatrix = innovChecker.get(batch);
            if (innovMatrix == null)
                innovMatrix = new double[batchSize][];
            
            // building coefficients array
            double[] coefficients = new double[batchSize];
            for (CodeVectorElement c: codeVector)
                coefficients[c.getBlock() - baseBlock] = c.getInnovCoefficient();
            
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
                        
                        if (canDecode(batch) && !decoding) {
                            decoding = true;
                            (new Decoder()).start();
                        }
                        
                        return true;
                    }
                }
            }
        }
        
        logger.info("Received uninnovative packet for batch " + batch);
        return false;
    }
    
    private boolean canDecode(int batch) {
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
            decoding = false;
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
                        decodedPackets.put(block.toBigInteger().toByteArray());
                    }
                } catch (InterruptedException e) {
                    logger.log(Level.SEVERE, e.getMessage(), e);
                    System.exit(1);
                }
                
                innovChecker.remove(nextDecBatch);
                packetBuffer.remove(nextDecBatch);
                
                nextDecBatch++;
                nextDecBlock+= batchSize;
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
    
    public short nextCoefficient() {
        return (short) ( random.nextInt( (2 * Byte.MAX_VALUE) + 1) - Byte.MAX_VALUE );
    }
}
