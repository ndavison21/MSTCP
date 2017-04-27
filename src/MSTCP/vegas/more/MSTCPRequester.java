package MSTCP.vegas.more;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.InetAddress;
import java.util.Random;
import java.util.Vector;
import java.util.logging.Handler;
import java.util.logging.Logger;

public class MSTCPRequester {
    final Logger logger;
    
    Vector<MSTCPRequesterConnection> connections;    // active connections (TODO: Replace failed connections)
    MSTCPInformation mstcpInformation;               // information about the MSTCP connections

    final InetAddress recvAddr;
    int nextRecvPort;                                // next port on which we receive ACKs + Data
    
    SourceCoder sourceCoder; // handles the decoding of packets. Initialised by first requester connection
    
    final int total_alpha = Utils.total_alpha;
    Double total_rate = 0.0;
    
    short prevReqBatch = 0; // previous batch requested (used when requesting dropped packets)
    short nextReqBatch = 0; // next batch to request
    int nextReqBlock   = 0; // first block of the new batch to request
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
        RandomAccessFile raf = new RandomAccessFile(file, "rw");
        
        // get decoded packets and write to file
        DecodedBlock block;
        long remainingBytes = mstcpInformation.fileSize;
        while (remainingBytes > 0) {
            block = sourceCoder.decodedBlocks.take();
            raf.seek(block.block * Utils.blockSize);
            raf.write(block.data, 1, block.data.length - 1);
            remainingBytes -= (block.data.length - 1);
        }
        
        logger.info("Closing Connections.");
        raf.close();
        transfer_complete = true;
        for (MSTCPRequesterConnection c: connections) {
            c.close();
        }
        
        for (MSTCPRequesterConnection c: connections) {
            synchronized(c.socket) {
                while (!c.socket.isClosed()) {
                    c.socket.wait();
                }
            }
        }
        
        logger.info("We Done here.");
        
        for (Handler handler: logger.getHandlers())
            handler.close();
    }
    
    // called by connections, returns next needed block and records which connection it'll come on
    public synchronized CodeVectorElement[] codeVector(int recvPort, double p_drop) {
        short batch = nextReqBatch;
        int batchSize = -1;
        
        if (batch >= sourceCoder.fileBatches) { // have we made requests for all batches
            boolean complete = true;
            for (short i=0; i < sourceCoder.fileBatches; i++) { // find any batches for which we need to repeat requests
                if (sourceCoder.decodedBatches.contains(i))
                    continue;
                batchSize = Math.min(Utils.batchSize, sourceCoder.fileBlocks - i * Utils.batchSize);
                if (batchSize < 0)
                    batchSize = sourceCoder.fileBlocks;
                
                if (!sourceCoder.packetBuffer.containsKey(i) || sourceCoder.packetBuffer.get(i).size() < batchSize) {
                    batch = i;
                    complete = false;
                    if (prevReqBatch != batch)
                        break;
                }
            }
            
            if (complete) { // got all data, just need to decode.
                logger.info("Got all data, waiting for decocde.");
                return null;
            }
            logger.info("Additional Request for batch " + batch + ".");
        }
        
        batchSize = Math.min(Utils.batchSize, sourceCoder.fileBlocks - (batch * Utils.batchSize));
        if (batchSize <= 0)
            batchSize = sourceCoder.fileBlocks;
        
        prevReqBatch = batch;
            
        logger.info("Request for batch " + batch + " on connection " + recvPort);
        int baseBlock = (batch == nextReqBatch ? nextReqBlock : batch * Utils.batchSize);
        
        
        CodeVectorElement[] codeVector = new CodeVectorElement[batchSize];
        
        for (int i=0; i<batchSize; i++)
            codeVector[i] = new CodeVectorElement((short) (baseBlock + i), sourceCoder.nextCoefficient(batchSize));
        
        if (batch == nextReqBatch) {
            nextBatchReqs += (1.0 / (1.0 - p_drop)) - 1.0; // redundancy
            nextBatchReqs -= 1; // packet sent
            
            if (nextBatchReqs < 0.5) {
                nextReqBatch++;
                nextReqBlock+= batchSize;
                batchSize = Math.min(Utils.batchSize, sourceCoder.fileBlocks - nextReqBatch * Utils.batchSize);
                if (batchSize < 0)
                    batchSize = sourceCoder.fileBlocks;
                nextBatchReqs = nextBatchReqs < 0 ? batchSize : nextBatchReqs + batchSize; // preserve redundancy built up
            }
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
        new MSTCPRequester("192.168.1.1", "192.168.2.1", 14000, 16000, "./", args[0]);
    }
}
