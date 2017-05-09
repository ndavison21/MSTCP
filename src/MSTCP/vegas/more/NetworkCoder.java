package MSTCP.vegas.more;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Random;
import java.util.Vector;
import java.util.logging.Level;
import java.util.logging.Logger;


public class NetworkCoder {
    final Logger logger;
    
    int fileBlocks = -1;
    
    Bool preEncoding = new Bool(false);
    
    HashMap<Integer, double[][]> innovChecker = new HashMap<Integer, double[][]>(); // maps batches to their innovative checker matrices
    HashMap<Integer, Vector<MOREPacket>> packetBuffer = new HashMap<Integer, Vector<MOREPacket>>(); // maps batches to packets
    HashMap<Integer, MOREPacket> preEncodedPackets = new HashMap<Integer, MOREPacket>(); // maps batches to their pre-encoded packet

    Random random = new Random();
    
    public NetworkCoder(Logger logger, long fileSize) {
        this.logger = logger;
        this.fileBlocks = (int) (fileSize / Utils.blockSize + (fileSize % Utils.blockSize > 0 ? 1: 0));
    }
    
    
    public MOREPacket processPacket(final MOREPacket more) {
           
        CodeVectorElement[] codeVector = more.getCodeVector();
        int batch = more.getBatch(); // the batch this packet belongs to
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
                } else
                    logger.info("Received uninnovative packet for batch " + batch);
                toSend = preEncodedPackets.remove(more.getBatch());
                if (toSend == null) // happens if first packet of a batch is not innovative
                    toSend = more;
                // for (CodeVectorElement c: toSend.getCodeVector())
                //     logger.info("Block " + c.getBlock() + " with coefficient " + c.getCoefficient());
                logger.info("Encoded Data " + toSend.getEncodedData());
                
                preEncoding.b = true;
                
                (new Thread() {
                    public void run() {
                        generatePreEncodedPacket(more.getBatch());
                    }
                }).start();
            }
        } catch (InterruptedException e) {
            logger.log(Level.SEVERE, e.getMessage(), e);
            e.printStackTrace();
            System.exit(1);
        }
        
        return toSend;
    }
    
    private void generatePreEncodedPacket(int batch) {
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
            int baseBlock = preEncoded.getBatch() * Utils.batchSize;
            
            CodeVectorElement[] prevCodeVector = preEncoded.getCodeVector();
            CodeVectorElement[] innovCodeVector = pktToAdd.getCodeVector();
            LinkedList<CodeVectorElement> newCodeVectorList = new LinkedList<CodeVectorElement>();
            
            short i=0, j=0, k=0;
            int block;
            short coefficient;
            for (i=0; i<batchSize; i++) {
                block = baseBlock + i;
                coefficient = 0; //prevCodeVector[i].getCoefficient() + innovCodeVector[i].getCoefficient();
                if (j < prevCodeVector.length && prevCodeVector[j].getBlock() == block)
                    coefficient += prevCodeVector[j++].getCoefficient();
                if (k < innovCodeVector.length && innovCodeVector[k].getBlock() == block)
                    coefficient += innovCodeVector[k++].getCoefficient();
                
                newCodeVectorList.add(new CodeVectorElement(block, coefficient));
            }
            
            CodeVectorElement[] newCodeVector = new CodeVectorElement[newCodeVectorList.size()];
            newCodeVectorList.toArray(newCodeVector);
            
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
