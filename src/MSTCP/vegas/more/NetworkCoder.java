package MSTCP.vegas.more;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.Random;
import java.util.Vector;
import java.util.logging.Level;
import java.util.logging.Logger;


public class NetworkCoder {
    final Logger logger;
    
    int fileBlocks = -1;
    
    Bool preEncoding = new Bool(false);
    
    HashMap<Short, double[][]> innovChecker = new HashMap<Short, double[][]>(); // maps batches to their innovative checker matrices
    HashMap<Short, Vector<MOREPacket>> packetBuffer = new HashMap<Short, Vector<MOREPacket>>(); // maps batches to packets
    HashMap<Short, MOREPacket> preEncodedPackets = new HashMap<Short, MOREPacket>(); // maps batches to their pre-encoded packet

    Random random = new Random();
    
    public NetworkCoder(Logger logger, long fileSize) {
        this.logger = logger;
        this.fileBlocks = (int) (fileSize / Utils.blockSize + (fileSize % Utils.blockSize > 0 ? 1: 0));
    }
    
    
    public MOREPacket processPacket(final MOREPacket more) {
           
        CodeVectorElement[] codeVector = more.getCodeVector();
        short batch = more.getBatch(); // the batch this packet belongs to
        int baseBlock = batch * Utils.batchSize;
        
        int batchSize = Math.min(Utils.batchSize, fileBlocks - batch * Utils.batchSize);
        if (batchSize < 0)
            batchSize = fileBlocks;

        // building coefficients array
        double[] coefficients = new double[batchSize];
        for (CodeVectorElement c: codeVector) {
            coefficients[c.getBlock() - baseBlock] = c.getInnovCoefficient();
        }
        
        double[][] innovMatrix = innovChecker.get(batch);
        if (innovMatrix == null)
            innovMatrix = new double[batchSize][];
        
        MOREPacket toSend = null;
        try {
            synchronized(preEncoding) {
                while(preEncoding.b == true)
                    preEncoding.wait();
                
                /// FIRST: Check if packet is innovative ///     
                if (isInnovative(batch, batchSize, coefficients, innovMatrix)) {
                    /// SECOND: Is innovative so admit packet to memory
                    logger.info("Received innovative packet for batch " + batch);
                    
                    Vector<MOREPacket> batchBuffer = packetBuffer.get(batch);
                    if (batchBuffer == null)
                        batchBuffer = new Vector<MOREPacket>();
                    
                    batchBuffer.add(more);
                    packetBuffer.put(batch, batchBuffer);
                    innovChecker.put(batch, innovMatrix); // innovMatrix update by isInnovative(...)
                    
                    /// THIRD: Update pre-encoded packet
                    updatePreEncodedPacket(more);
                }
                toSend = preEncodedPackets.remove(more.getBatch());
                preEncoding.b = true;
                
                (new Thread() {
                    public void run() {
                        generatePreEncodedPacket(more.getBatch());
                    }
                }).start();
            }
        } catch (InterruptedException e) {
            logger.log(Level.SEVERE, e.getMessage(), e);
            System.exit(1);
        }
        
        return toSend;
    }
    
    private void generatePreEncodedPacket(short batch) {
        synchronized(preEncoding) {
            preEncodedPackets.remove(batch);
            
            Vector<MOREPacket> batchBuffer = packetBuffer.get(batch);
            if (batchBuffer.size() <= 3)
                for (MOREPacket more: batchBuffer)
                    updatePreEncodedPacket(more);
            else {
                // choose 3 packets
                int pkt = random.nextInt(batchBuffer.size());
                for (int i=0; i<3; i++) {
                    pkt = random.nextInt(batchBuffer.size());
                    updatePreEncodedPacket(batchBuffer.get(pkt));
                }
            }
            
            preEncoding.b = false;
            preEncoding.notify();
        }
    }
    
    private void updatePreEncodedPacket(MOREPacket pktToAdd) {
        MOREPacket preEncoded = preEncodedPackets.get(pktToAdd.getBatch());
        
        if (preEncoded == null) {
            preEncoded = pktToAdd.clone();
        } else {
            int batchSize = Math.min(Utils.batchSize, fileBlocks - preEncoded.getBatch() * Utils.batchSize);
            if (batchSize < 0)
                batchSize = fileBlocks;
            short baseBlock = (short) (preEncoded.getBatch() * Utils.batchSize);
            
            CodeVectorElement[] prevCodeVector = preEncoded.getCodeVector();
            CodeVectorElement[] innovCodeVector = pktToAdd.getCodeVector();
            CodeVectorElement[] newCodeVector = new CodeVectorElement[batchSize];
            
            short i=0, j=0, k=0;
            short block;
            int coefficient;
            for (i=0; i<batchSize; i++) {
                block = (short) (baseBlock + i);
                coefficient = 0;
                if (prevCodeVector[j].getBlock() == block)
                    coefficient += prevCodeVector[j++].getCoefficient();
                if (innovCodeVector[k].getBlock() == block)
                    coefficient += innovCodeVector[k++].getCoefficient();
                
                newCodeVector[i] = new CodeVectorElement(block, coefficient);
            }
            
            BigInteger encodedData = preEncoded.getEncodedData().add(pktToAdd.getEncodedData());
    
            preEncoded.setCodeVector(newCodeVector);
            preEncoded.setEncodedData(encodedData);
        }
        
        preEncodedPackets.put(preEncoded.getBatch(), preEncoded);
}
    
    private boolean isInnovative(int batch, int batchSize, double[] coefficients, double[][] innovMatrix) {
        
        for (int i=0; i<batchSize; i++) {
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
}
