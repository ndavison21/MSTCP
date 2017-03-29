package MSTCP.vegas.more;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.InetAddress;
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
    
    short nextBlock = 0; // next block to request
    
    final int total_alpha = Utils.total_alpha;
    Double total_rate = 0.0;
    
    boolean transfer_complete = false;
    
    public MSTCPRequester(String addr, int recvPort, int dstPort, String path, String filename) throws InterruptedException, IOException {
        logger = Utils.getLogger(this.getClass().getName());
        logger.info("Starting up MSTCP-Vegas Connection. File: " + filename);
        
        this.recvAddr = InetAddress.getByName(Utils.getIPAddress(logger));
        this.nextRecvPort = recvPort;
        this.mstcpInformation = new MSTCPInformation(recvAddr.getAddress(), recvPort, filename, (new Random()).nextInt());
        this.connections = new Vector<MSTCPRequesterConnection>(Utils.noOfSources);
        this.receivedPackets = new LinkedBlockingQueue<MOREPacket>();
        
        // Starting first connections
        logger.info("Starting first connection (" + InetAddress.getByName(addr) + ", " + dstPort + ")");
        MSTCPRequesterConnection conn = new MSTCPRequesterConnection(addr, nextRecvPort++, dstPort, this, false);
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
            // TODO: coded data not just block numbers
            more = receivedPackets.take();
            if (more.getCodeVector()[0] < nextBlock)
                continue;
            
            logger.info("Received block " + nextBlock); 
            nextBlock++;
            
            fos.write(more.getEncodedData());
            if (more.getCodeVector()[0] == (mstcpInformation.fileSize / Utils.blockSize) + 1)
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
    public synchronized short[] codeVector(int recvPort) {
        if (nextBlock > (mstcpInformation.fileSize / Utils.blockSize) + 1)
            return null;
        logger.info("Requesting block " + nextBlock + " on connection " + recvPort);
        
        return (new short[]{nextBlock});
    }

    public synchronized void adjust_weights() {
        for (MSTCPRequesterConnection c: this.connections)
            if (c.equilibrium_rate != 0)
                c.weight = c.equilibrium_rate / total_rate;
    }
}
