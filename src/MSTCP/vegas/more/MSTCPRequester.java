package MSTCP.vegas.more;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.HashMap;
import java.util.Random;
import java.util.Vector;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.Logger;

public class MSTCPRequester {
    final Logger logger;
    
    Vector<ConnectionHandler> connections;  // active connections
    MSTCPInformation mstcpInformation;      // information about the MSTCP connections

    final InetAddress recvAddr; // local address
    int nextRecvPort;           // next port to start a connection handler on
    
    SourceCoder sourceCoder; // handles the decoding of packets. Initialised by first requester connection (TODO: would be nice if it was initialised here rather than by the connection)
    
    final int total_alpha = Utils.total_alpha;
    Double total_rate = 0.0; // congestion control parameter (see wVegas paper)
    
    int prevReqBatch = 0; // previous batch requested (used when requesting dropped packets)
    int nextReqBatch = 0; // next batch to request
    int nextReqBlock   = 0; // first block of the new batch to request
    double nextBatchReqs = 0.0; // number of remaining requests to make for current batch (including redundancy for loss rate) 
    
    private HashMap<Integer, Integer> batchRows = new HashMap<Integer, Integer>(); // which row of the coding matrix we want to use next for each batch
    
    boolean transfer_complete = false;

    public MSTCPRequester(String recvAddr, String dstAddr, int recvPort, int dstPort, String path, String filename) throws InterruptedException, IOException {
        logger = Utils.getLogger(this.getClass().getName());
        logger.info("Starting up MSTCP-Vegas Connection. File: " + filename);
        
        this.recvAddr = InetAddress.getByName(recvAddr);
        this.nextRecvPort = recvPort;
        this.mstcpInformation = new MSTCPInformation(this.recvAddr.getAddress(), recvPort, filename, (new Random()).nextInt());
        this.connections = new Vector<ConnectionHandler>(Utils.noOfSources);
        
        int routerPort = 15000;
        
        // Starting first connections
        logger.info("Starting first connection (" + InetAddress.getByName(dstAddr) + ", " + dstPort + ")");
        MSTCPRequesterConnection conn = new MSTCPRequesterConnection(dstAddr, nextRecvPort++, dstPort, routerPort, this, false);
        connections.addElement(new ConnectionHandler(recvPort, this, conn, routerPort));
        
        // Start connections with other sources
        synchronized(mstcpInformation) {
            while(mstcpInformation.sources == null)
                mstcpInformation.wait();
            for (SourceInformation s: mstcpInformation.sources) {
                if (connections.size() >= Utils.noOfConnections)
                    break;
                if (s.connected < Utils.noOfPaths && s.connected < s.ports.size()) {
                    for (int port: s.ports.keySet()) {
                        if (!s.ports.get(port)) {
                            logger.info("Connecting to " + s.address + " on port " + port);
                            conn = new MSTCPRequesterConnection(s.address, nextRecvPort++, port,  routerPort, this, true);
                            connections.add(new ConnectionHandler(recvPort, this, conn, routerPort));
                            s.ports.put(port, true);
                            s.tried.put(port, true);
                            s.connected++;
                            break;
                        }
                    }
                }
            }
        }
        
        // start writing data to file
        File file = new File(path + "received_" + filename);
        file.delete();
        file.createNewFile();
        RandomAccessFile raf = new RandomAccessFile(file, "rw");
        
        /** COMPARING BLOCKS TO THE ORIGINAL **/
//        File original = new File(path + filename);
//        RandomAccessFile oraf = new RandomAccessFile(original, "r");
        /**************************************/
        
        // get decoded packets and write to file
        DecodedBlock block;
        long bytesWritten = 0;
        while (bytesWritten < mstcpInformation.fileSize) {
            block = sourceCoder.decodedBlocks.take();
            raf.seek(block.block * Utils.blockSize);
            raf.write(block.data, 1, block.data.length - 1);
            bytesWritten += block.data.length - 1;
            
            /** COMPARING BLOCKS TO THE ORIGINAL **/
//            if (Utils.decode) {
//                oraf.seek(block.block * Utils.blockSize);
//                byte[] obytes = new byte[block.data.length];
//                obytes[0] = 1;
//                oraf.read(obytes, 1, obytes.length - 1);
//                if (!Arrays.equals(block.data, obytes))
//                    System.out.println("Original is Different! Block " + block.block);
//            }
            /**************************************/
            
//            System.out.println("Block " + (block.block < 100 ? block.block < 10 ? "  " : " " : "") + block.block + " length " + block.data.length + " written " + bytesWritten + " bytes");
        }
        
        logger.info("Closing Connections.");
        raf.close();
//        oraf.close();
        transfer_complete = true;
        for (ConnectionHandler c: connections) {
            c.close();
        }
        
        for (ConnectionHandler c: connections) {
            synchronized(c.conn.socket) {
                while (!c.conn.socket.isClosed()) {
                    c.conn.socket.wait();
                }
            }
        }
        
        logger.info("We Done here.");
        
        for (Handler handler: logger.getHandlers())
            handler.close();
    }
    
    private class ConnectionHandler extends Thread {
        MSTCPRequesterConnection conn;
        MSTCPRequester requester;
        boolean closed = false;
        int routerPort;
        
        public void run() {
            try {
                for (;;) {
                    synchronized(conn.active) {
                        while (!closed && conn.active.b) {
                            logger.info("Active is True. Waiting.");
                            conn.active.wait();
                        }
                    }
                    
                    if (closed)
                        break;
                    
                    synchronized(mstcpInformation) {
                        String prevAddr = conn.dstAddr.getHostAddress();
                        int prevPort = conn.dstPort;
                        
                        SourceInformation newSource = null;
                        int newPort = prevPort;
                        boolean tried = true;
                        
                        conn.close();
                        
                        for (SourceInformation s: mstcpInformation.sources) {
                            if (s.address.equals(prevAddr) && s.ports.get(prevPort)) {
                                s.ports.put(prevPort, false);
                                s.connected--;
                            }
                            if (s.connected < Utils.noOfPaths && s.connected < s.ports.size()) {
                                for (int p: s.ports.keySet()) {
                                    if (tried && !s.ports.get(p)) {
                                        newSource = s;
                                        newPort = p;
                                        tried = s.tried.get(p);
                                    }
                                }
                            }
                        }
                        logger.info("Connecting to " + newSource.address + " on port " + newPort);
                        if (routerPort == prevPort)
                            routerPort = newPort;
                        conn = new MSTCPRequesterConnection(newSource.address, conn.recvPort, newPort, routerPort, requester, true);
                        newSource.ports.put(newPort, true);
                        newSource.tried.put(newPort, true);
                        newSource.connected++;
                    }
                }
                
                logger.info("Closed connection handler");
                
            } catch(InterruptedException | UnknownHostException | SocketException e) {
                logger.log(Level.SEVERE, e.getMessage(), e);
                e.printStackTrace();
                System.exit(1);
            }
        }
        
        public void close() {
            logger.info("Closing Connection Handler.");
            synchronized (conn.active) {
                this.closed = true;
                String addr = conn.dstAddr.getHostAddress();
                int port = conn.dstPort;
                conn.close();
                synchronized (mstcpInformation) {
                    for (SourceInformation s: mstcpInformation.sources) {
                        if (s.address.equals(addr)) {
                            s.ports.put(port, false);
                            s.connected--;
                            break;
                        }
                    }
                }
                conn.active.b = false;
                conn.active.notifyAll();
            }
            
        }
        
        
        public ConnectionHandler(int recvPort, MSTCPRequester requester,  MSTCPRequesterConnection conn, int routerPort) {
            this.requester = requester;
            this.conn = conn;
            this.routerPort = routerPort;
            this.start();
        }
    }
    
    
    // called by connections, returns next needed block and records which connection it'll come on
    public synchronized CodeVectorElement[] codeVector(int recvPort, double p_drop) {
        int batch = nextReqBatch;
        int batchSize = -1;
        
        if (batch >= sourceCoder.fileBatches) { // have we made requests for all batches
            boolean complete = true;
            for (int i=0; i < sourceCoder.fileBatches; i++) { // find any batches for which we need to repeat requests
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
        
        Utils.requests_logger.fine(System.nanoTime() + " " + recvPort + " " + p_drop);
        batchSize = Math.min(Utils.batchSize, sourceCoder.fileBlocks - (batch * Utils.batchSize));
        if (batchSize <= 0)
            batchSize = sourceCoder.fileBlocks;
        
        prevReqBatch = batch;
            
        int baseBlock = (batch == nextReqBatch ? nextReqBlock : batch * Utils.batchSize);
        
        Integer nextRow = batchRows.get(batch);
        nextRow = nextRow == null ? 0 : nextRow;
        batchRows.put(batch, nextRow + 1);
        byte[] coefficients = sourceCoder.nextRow(nextRow, batchSize);
        
        logger.info("Request for batch " + batch + " row " + nextRow + " on connection " + recvPort);
        
        CodeVectorElement[] codeVector = new CodeVectorElement[batchSize];
        for (int i=0; i<codeVector.length; i++) {
            codeVector[i] = new CodeVectorElement(baseBlock + i, i < coefficients.length ? coefficients[i] : sourceCoder.nextCoefficient());
        }
        
        if (batch == nextReqBatch) {
            if (nextRow < batchSize) {
                nextBatchReqs += 1.5 * ((1.0 / (1.0 - p_drop)) - 1.0); // redundancy
    
            } else if (nextBatchReqs < 0.5 || sourceCoder.decodedBatches.contains(batch)) {
                nextReqBatch++;
                nextReqBlock+= batchSize;
                batchSize = Math.min(Utils.batchSize, sourceCoder.fileBlocks - nextReqBatch * Utils.batchSize);
                if (batchSize < 0)
                    batchSize = sourceCoder.fileBlocks;
                nextBatchReqs = nextBatchReqs < 0 ? batchSize : nextBatchReqs + batchSize; // preserve redundancy built up
            }
        }
        nextBatchReqs -= 1; // packet sent
        
        return codeVector;
    }


    public synchronized void adjust_weights() {
        synchronized(total_rate) {
            for (ConnectionHandler c: this.connections)
                if (c.conn.equilibrium_rate != 0 && c.conn.active.b)
                    c.conn.weight = c.conn.equilibrium_rate / total_rate;
        }
    }
    
}
