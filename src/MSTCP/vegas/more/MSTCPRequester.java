package MSTCP.vegas.more;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.util.Random;
import java.util.Vector;
import java.util.logging.Logger;

public class MSTCPRequester {
    final Logger logger;
    
    Vector<MSTCPRequesterConnection> connections;    // active connections (TODO: Replace failed connections)
    MSTCPInformation mstcpInformation;               // information about the MSTCP connections

    final InetAddress recvAddr;
    int nextRecvPort;                                // next port on which we receive ACKs + Data
    
    NetworkCoder networkCoder; // handles the decoding of packets. Initialised by first requester connection
    
    final int total_alpha = Utils.total_alpha;
    Double total_rate = 0.0;
    
    int nextReqBatch  = 0; // next batch to request
    int nextReqBlock  = 0; // first block of the new batch to request
    double nextBatchReqs = 0.0; // number of remaining requests to make for current batch (including redundancy for loss rate) 
    
    boolean transfer_complete = false;
    
    public MSTCPRequester(String recvAddr, String dstAddr, int recvPort, int dstPort, String path, String filename) throws InterruptedException, IOException {
        logger = Utils.getLogger(this.getClass().getName());
        logger.info("Starting up MSTCP-Vegas Connection. File: " + filename);
        
        this.recvAddr = InetAddress.getByName(recvAddr);
        this.nextRecvPort = recvPort;
        this.mstcpInformation = new MSTCPInformation(this.recvAddr.getAddress(), recvPort, filename, (new Random()).nextInt());
        this.connections = new Vector<MSTCPRequesterConnection>(Utils.noOfSources);
        
        // Starting first connections
        logger.info("Starting first connection (" + InetAddress.getByName(dstAddr) + ", " + dstPort + ")");
        MSTCPRequesterConnection conn = new MSTCPRequesterConnection(dstAddr, nextRecvPort++, dstPort, this, false);
        // networkCoder = new NetworkCoder(logger, mstcpInformation.fileSize, true); TODO: create here rather than in connection
        connections.addElement(conn);
        
        // Start connections with other sources
        for (SourceInformation s: mstcpInformation.sources) {
            if (connections.size() >= Utils.noOfSources)
                break;
            if (!s.connected) {
                logger.info("Connecting to " + s.address + " on port " + s.port);
                conn = new MSTCPRequesterConnection(s.address, nextRecvPort++, s.port, this, true);
                connections.add(conn);
                s.connected = true;
            }
        }
        
        // start writing data to file
        File file = new File(path + "received_" + filename);
        FileOutputStream fos = new FileOutputStream(file);
        
        // get decoded packets and write to file
        byte[] data;
        long remainingBytes = mstcpInformation.fileSize;
        int toWrite = 0;
        while (remainingBytes > 0) {
            data = networkCoder.decodedPackets.take();
            toWrite = (int) Math.min( Math.min(data.length, Utils.blockSize), remainingBytes);
            fos.write(data, 0, Math.min(data.length, Utils.blockSize));
            remainingBytes -= toWrite;
        }
        
        logger.info("Closing Connections.");
        fos.close();
        transfer_complete = true;
        for (MSTCPRequesterConnection c: connections) {
            c.close();
        }
        logger.info("We Done here.");
    }
    
    // called by connections, returns next needed block and records which connection it'll come on
    public synchronized CodeVectorElement[] codeVector(int recvPort, double p_drop) {
        int batch = nextReqBatch;
        int batchSize = Math.min(Utils.batchSize, networkCoder.fileBlocks - (nextReqBatch * Utils.batchSize));
        if (batchSize < 0)
            batchSize = networkCoder.fileBlocks;
        
        if (batch >= networkCoder.fileBatches) { // have we made requests for all batches
            
            for (int i=0; i<networkCoder.fileBatches; i++) { // find any batches we need to repeat requests for
                if (networkCoder.packetBuffer.containsKey(i)) {
                    batchSize = Math.min(Utils.batchSize, networkCoder.fileBlocks - nextReqBatch * Utils.batchSize);
                    if (batchSize < 0)
                        batchSize = networkCoder.fileBlocks;
                    if (networkCoder.packetBuffer.get(i).size() < batchSize) {
                        batch = i;
                        break;
                    }
                }
            }
            
            if (batch >= networkCoder.fileBatches) { // got all data, just need to decode.
                logger.info("Made all requests, closing connections.");
                return null;
            }
        }
            
        logger.info("Request for batch " + nextReqBatch + " on connection " + recvPort);
        
        
        CodeVectorElement[] codeVector = new CodeVectorElement[batchSize];
        
        for (int i=0; i<batchSize; i++)
            codeVector[i] = new CodeVectorElement((short) (nextReqBlock + i), networkCoder.nextCoefficient());
        
        nextBatchReqs += p_drop; // redundancy
        nextBatchReqs -= 1; // packet sent
        
        if (nextBatchReqs < 0.5) {
            nextReqBatch++;
            nextReqBlock+= batchSize;
            batchSize = Math.min(Utils.batchSize, networkCoder.fileBlocks - nextReqBatch * Utils.batchSize);
            if (batchSize < 0)
                batchSize = networkCoder.fileBlocks;
            nextBatchReqs = batchSize;
        }
        return codeVector;
    }


    public synchronized void adjust_weights() {
        for (MSTCPRequesterConnection c: this.connections)
            if (c.equilibrium_rate != 0)
                c.weight = c.equilibrium_rate / total_rate;
    }
    
    
    public static void main(String[] args) throws InterruptedException, IOException {
        System.out.println("Note: Setup is for Triangle Network with PC-1 requesting from PC-2 and PC-3");
        System.out.println("Args: <filename>");
        new MSTCPRequester("192.168.1.1", "192.168.2.1", 14000, 15000, "./", args[0]);
    }
}
