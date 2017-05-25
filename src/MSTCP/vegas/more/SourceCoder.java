package MSTCP.vegas.more;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Random;
import java.util.Vector;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.logging.Level;
import java.util.logging.Logger;

public class SourceCoder extends Thread {
    final Logger logger;
    
    int fileBlocks  = -1; // filesize in blocks
    int fileBatches = -1; // filesize in batches
    
    LinkedBlockingQueue<MOREPacket> receivedPackets = new LinkedBlockingQueue<MOREPacket>(); // connections add their received packets to this
    LinkedBlockingQueue<DecodedBlock> decodedBlocks = new LinkedBlockingQueue<DecodedBlock>(); // pass decoded packets to the requester

    
    HashMap<Integer, double[][]> innovChecker = new HashMap<Integer, double[][]>(); // maps batches to their innovative checker matrices
    HashMap<Integer, Vector<MOREPacket>> packetBuffer = new HashMap<Integer, Vector<MOREPacket>>(); // maps batches to packets
    HashSet<Integer> decodedBatches = new HashSet<Integer>(); // maintains set of batches which have been (or are being) decoded
    
    Random random = new Random();
    
    public SourceCoder(Logger logger, long fileSize) {
        this.logger = logger;
        this.fileBlocks = (int) (fileSize / Utils.blockSize + (fileSize % Utils.blockSize > 0 ? 1: 0));
        this.fileBatches = (fileBlocks / Utils.batchSize) + (fileBlocks % Utils.batchSize > 0 ? 1 : 0);
        this.start();
    }
    
    public void run() {
        try {
            while(decodedBatches.size() < fileBatches)
                processPacket(receivedPackets.take());
        } catch (InterruptedException e) {
            logger.log(Level.SEVERE, e.getMessage(), e);
            e.printStackTrace();
            System.exit(1);
        }
    }
    
    public void processPacket(MOREPacket more) {
        CodeVectorElement[] codeVector = more.getCodeVector();
        final int batch = more.getBatch(); // the batch this packet belongs to
        int baseBlock = batch * Utils.batchSize;
        
        int batchSize = Math.min(Utils.batchSize, fileBlocks - batch * Utils.batchSize);
        if (batchSize < 0)
            batchSize = fileBlocks;

        // building coefficients array
        double[] coefficients = new double[batchSize];
        try {
        for (CodeVectorElement c: codeVector) {
            coefficients[c.getBlock() - baseBlock] = c.getInnovCoefficient();
        }
        } catch(Exception e) {
            e.printStackTrace();
            logger.log(Level.SEVERE, e.getMessage(), e);
            System.exit(1);
        }
        

        
        synchronized(decodedBatches) {
            double[][] innovMatrix = innovChecker.get(batch);
            if (innovMatrix == null)
                innovMatrix = new double[batchSize][];
            /// FIRST: Check if packet is innovative ///     
            if (!decodedBatches.contains(batch) && isInnovative(batch, batchSize, coefficients, innovMatrix)) {
                /// SECOND: Is innovative so admit packet to memory
                logger.info("Received innovative packet for batch " + batch);
                Vector<MOREPacket> batchBuffer = packetBuffer.get(batch);
                if (batchBuffer == null)
                    batchBuffer = new Vector<MOREPacket>();
                
                batchBuffer.add(more);
                packetBuffer.put(batch, batchBuffer);
                innovChecker.put(batch, innovMatrix); // innovMatrix update by isInnovative(...)
                
                /// THIRD: Decode if possible
                if (canDecode(batch)) {
                    decodedBatches.add(batch); // put in a dummy so we do not reenter this branch for the same batch
                    (new Thread() {
                        public void run() {
                            decode(batch);
                        }
                    }).start();
                }
            } else {
                logger.info("Received uninnovative packet for batch " + batch + ". Is Decoded? " + decodedBatches.contains(batch));
            }
        }
    }
    
    private boolean canDecode(int batch) { // do we have enough DoFs to decode the batch
        int batchSize = Math.min(Utils.batchSize, fileBlocks - batch * Utils.batchSize);
        if (batchSize < 0)
            batchSize = fileBlocks;
        if (batch > fileBatches || packetBuffer.get(batch) == null)
            return false;
        return packetBuffer.get(batch).size() == batchSize;
    }
    
    
    public void decode(int batch) {
        Vector<MOREPacket> batchBuffer;
        synchronized(decodedBatches) {
            logger.info("Decoding Batch " + batch);
            batchBuffer = packetBuffer.remove(batch);
            innovChecker.remove(batch);
        }
        int batchSize = Math.min(Utils.batchSize, fileBlocks - batch * Utils.batchSize);
        if (batchSize < 0)
            batchSize = fileBlocks;
        int baseBlock = batch * Utils.batchSize;
        
        if (Utils.decode) {
            // set up coding matrix
            BigDecimal[][] codingMatrix = new BigDecimal[batchSize][batchSize];
            BigDecimal[] codedData = new BigDecimal[batchSize];
            
            
            for (int i=0; i<batchSize; i++) {
                CodeVectorElement[] codeVector = batchBuffer.get(i).getCodeVector();
                int k = 0;
                for (int j=0; j<batchSize; j++) {
                    // logger.info("Block " + codeVector[k].getBlock() + " with coefficient " + codeVector[k].getCoefficient());
                    codingMatrix[i][j] = new BigDecimal(k < codeVector.length && codeVector[k].getBlock() == baseBlock + j ? codeVector[k++].getCoefficient() : 0);
                }
                codedData[i] = new BigDecimal(batchBuffer.get(i).getEncodedData());
                // logger.info("Batch " + batch + " Encoded Data " + codedData[i]);
            }
            
            // get the inverse
            BigDecimal[][] inverseCodingMatrix = null;
            inverseCodingMatrix = invert(codingMatrix);
            BigDecimal block;
            
            
            // multiple coded data by inverse matrix to recover packets
            for (int i=0; i<batchSize; i++) {
                block = new BigDecimal(0);
                for (int j=0; j<batchSize; j++) {
                    BigDecimal inverseCoefficient = inverseCodingMatrix[i][j];
                    BigDecimal codedBlock = codedData[j];
                    block = block.add(codedBlock.multiply(inverseCoefficient));
                }
                BigInteger data = block.setScale(0, RoundingMode.HALF_UP).toBigInteger();
                decodedBlocks.add(new DecodedBlock(baseBlock + i, data.toByteArray()));
            }
        } else {
            for (int i=0; i<batchSize; i++) {
                decodedBlocks.add(new DecodedBlock(baseBlock + i, new byte[Utils.transferSize]));
            }
        }
        logger.info("Decoded Batch " + batch);
        

    }
    
    private boolean isInnovative(int batch, int batchSize, double[] coefficients, double[][] innovMatrix) {
        int i = 0;
        for (i=0; i<batchSize; i++) {
            if (coefficients[i] != 0) { // if u[i] != 0
                double ui = coefficients[i];
                if (innovMatrix[i] != null) { // if M[i] exists
                    // u <- u - M[i]u[i]
                    for (int j=0; j<batchSize; j++)
                        coefficients[j] -= innovMatrix[i][j] * ui;
                } else {
                    // M[i] <- u/u[i]
                    for (int j=0; j<batchSize; j++)
                        coefficients[j] /= ui;
                    innovMatrix[i] = coefficients;
                    return true;
                }
            }
        }
        return false;
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
    
    public byte nextCoefficient() {
        return (byte) (random.nextInt(2 * Byte.MAX_VALUE) - Byte.MAX_VALUE);
    }
    
    public byte[] nextRow(int row, int batchSize) {
        if (row < 256)
            return CodeMatrix.getRow(row);
        byte[] coefficients = new byte[batchSize];
        for (int i=0; i<batchSize; i++)
            coefficients[i] = (byte) (random.nextInt(2 * Byte.MAX_VALUE) - Byte.MAX_VALUE);
        return coefficients;
    }
}
