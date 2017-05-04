package MSTCP.vegas;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.util.Vector;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.logging.Level;
import java.util.logging.Logger;

public class MSTCPRequester {
    final Logger logger;
    
    Vector<MSTCPRequesterConnection> connections; // active connections (TODO: Replace failed connections)
    LinkedBlockingQueue<ConnectionToRequestMap> connectionToBlockQueue; // records which blocks requested on each connection
    MSTCPInformation mstcpInformation; // informations about the MSTCP connections
    final int baseRecvPort; // first port used for indexing Vector. TODO: find a better solution?
    int nextRecvPort;       // next port on which we receive ACKs + Data
    
    int nextBlock = 0; // next block to request
    
    final int total_alpha = Utils.total_alpha;
    Double total_rate = 0.0;
    
    boolean transfer_complete = false;
    
    public MSTCPRequester(String addr, int recvPort, int dstPort, String path, String filename) throws InterruptedException, IOException {
        logger = Utils.getLogger(this.getClass().getName());
        logger.info("Starting up MSTCP-Vegas Connection. File: " + filename);
        
        this.baseRecvPort = recvPort;
        this.nextRecvPort = baseRecvPort;
        this.mstcpInformation = new MSTCPInformation(recvPort, filename);
        this.connections = new Vector<MSTCPRequesterConnection>(Utils.noOfSources);
        this.connectionToBlockQueue = new LinkedBlockingQueue<ConnectionToRequestMap>();
        
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
        
        ConnectionToRequestMap map;
        TCPPacket tcpPacket;
        for(;;) {
            map = connectionToBlockQueue.take();
            tcpPacket = connections.get(map.connection - baseRecvPort).receivedData.take();
            fos.write(tcpPacket.getData());
            if (map.block == (mstcpInformation.fileSize / Utils.blockSize) + 1)
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
    public synchronized int blockToRequest(int recvPort) {
        if (nextBlock > (mstcpInformation.fileSize / Utils.blockSize) + 1)
            return -1;
        logger.info("Requesting block " + nextBlock + " on connection " + recvPort);
        try {
            connectionToBlockQueue.put(new ConnectionToRequestMap(recvPort, nextBlock));
        } catch (InterruptedException e) {
            logger.log(Level.SEVERE, e.getMessage(), e);
            e.printStackTrace();
            System.exit(1);
        }
        return nextBlock++;
    }

    public synchronized void adjust_weights() {
        for (MSTCPRequesterConnection c: this.connections)
            if (c.equilibrium_rate != 0)
                c.weight = c.equilibrium_rate / total_rate;
    }
}
