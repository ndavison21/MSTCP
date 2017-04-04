package MSTCP.vegas.more;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.util.Arrays;
import java.util.Random;
import java.util.Vector;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.logging.Logger;

public class MSTCPRequester {
    final Logger logger;
    
    Vector<MSTCPRequesterConnection> connections;    // active connections (TODO: Replace failed connections)
    LinkedBlockingQueue<MOREPacket> receivedPackets; // connections add their received packets to this
    MSTCPInformation mstcpInformation;               // information about the MSTCP connections

    final InetAddress recvAddr;
    int nextRecvPort;                                // next port on which we receive ACKs + Data
    
    NetworkCoder networkCoder;
    
    final int total_alpha = Utils.total_alpha;
    Double total_rate = 0.0;
    
    boolean transfer_complete = false;
    
    public MSTCPRequester(String recvAddr, String dstAddr, int recvPort, int dstPort, String path, String filename) throws InterruptedException, IOException {
        logger = Utils.getLogger(this.getClass().getName());
        logger.info("Starting up MSTCP-Vegas Connection. File: " + filename);
        
        this.recvAddr = InetAddress.getByName(recvAddr);
        this.nextRecvPort = recvPort;
        this.mstcpInformation = new MSTCPInformation(this.recvAddr.getAddress(), recvPort, filename, (new Random()).nextInt());
        this.connections = new Vector<MSTCPRequesterConnection>(Utils.noOfSources);
        this.receivedPackets = new LinkedBlockingQueue<MOREPacket>();
        
        // Starting first connections
        logger.info("Starting first connection (" + InetAddress.getByName(dstAddr) + ", " + dstPort + ")");
        MSTCPRequesterConnection conn = new MSTCPRequesterConnection(dstAddr, nextRecvPort++, dstPort, this, false);
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
        
        
        MOREPacket more;
        for(;;) {
            more = receivedPackets.take();
            
            if (networkCoder.isInnovative(more)) {
            	if (networkCoder.canDecode()) {
            		Vector<byte[]> data = networkCoder.decode();
            		for (byte[] d: data) {
            			fos.write(d, 0, Math.min(d.length, Utils.blockSize));
            		}
            	}
            }
            
            if (networkCoder.nextBatch > (mstcpInformation.fileSize / (Utils.blockSize * Utils.batchSize)) + 1)
                break;
        }
        
        logger.info("We Done here.");
        fos.close();
        transfer_complete = true;
        for (MSTCPRequesterConnection c: connections) {
            c.close();
        }
    }
    
    // called by connections, returns next needed block and records which connection it'll come on
    public void codeVector(int recvPort, CodeVectorElement[] codeVector) {
        if (networkCoder.nextBatch > (mstcpInformation.fileSize / (Utils.blockSize * Utils.batchSize)) + 1) {
        	Arrays.fill(codeVector, new CodeVectorElement((short) -1, (short) -1));
            return;
        }
        for (short i=0; i < Utils.batchSize; i++)
        	codeVector[i] = new CodeVectorElement((short) (networkCoder.baseBlock + i), networkCoder.random());
        
        logger.info("Request for batch " + networkCoder.nextBatch + " on connection " + recvPort);
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
