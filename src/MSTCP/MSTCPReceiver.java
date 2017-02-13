package MSTCP;

import java.io.File;
import java.io.FileOutputStream;
import java.util.Vector;
import java.util.concurrent.LinkedBlockingQueue;

public class MSTCPReceiver {
    static int headerSize  = 20;  // TCP Header (with no options): 20 bytes
    static int requestSize = 24;  // TCP Header: 20 , Request (block to send): 4 bytes
    static int blockSize   = 980;  // Send blocks of 980 bytes
    static int pktSize     = 1000; // TCP Header: 20, blocks <= 980, so 1000 Bytes total
    static int synLimit    = 50;   // number if times to try SYN before giving up
    static int noOfSources = 1;    // number of sources to connect to
    
    LinkedBlockingQueue<ConnectionToRequestMap> connectionToBlockQueue; // queue of elements mapping connections to the block requested on that connection 
    
    Vector<MSTCPReceiverConnection> connections; // active connections TODO: if connection fails then remove and try a different one
    boolean requestsComplete = false;           // requests from all blocks have been sent
    boolean transferComplete = false;            // data has been transferred just not written to file (no more requests needed)
    boolean receivingComplete = false;           // data has been written to file
    int nextConnectionID = 0;                    // connection identifiers
    int nextRecvPort;                            // ports to receive on
    
    MSTCPInformation mstcpInformation;           // information about the MSTCP connection
    int nextBlock = 0;                           // next block to request
    
    int alpha;
    int alpha_scale = 512; // optimise calculation of alpha
    int bytes_acked;
    int cwnd_total;
    
    

    
    
    public MSTCPReceiver(String addr, int recvPort, int dstPort, String path, String filename) {
        System.out.println("MSTCPReceiver: Starting Up MSTCP Connection. File: " + filename);
        this.mstcpInformation = new MSTCPInformation(0, filename);
        this.nextRecvPort = recvPort;
        connections = new Vector<MSTCPReceiverConnection>(noOfSources);
        connectionToBlockQueue = new LinkedBlockingQueue<ConnectionToRequestMap>();
        
        // start the first connection (sets filesize and sources)
        System.out.println("MSTCPReceiver: Connecting to " + addr + " on port " + dstPort);
        MSTCPReceiverConnection connection = new MSTCPReceiverConnection(addr, nextRecvPort++, dstPort, this, nextConnectionID++, false);
        connections.addElement(connection);
        
        try {
            File file = new File(path + "received_" + filename);
            FileOutputStream fos = new FileOutputStream(file);
            
            // start connections with other sources
            boolean unusedConnections = true; // are there connections we haven't tried
            while(connections.size() < MSTCPReceiver.noOfSources && unusedConnections) {
                unusedConnections = false;
                for (SourceInformation s: mstcpInformation.sources) {
                    if (!s.connected) {
                        unusedConnections = true;
                        System.out.println("MSTCPReceiver: Connecting to " + s.address + " on port " + s.port);
                        MSTCPReceiverConnection conn = new MSTCPReceiverConnection(s.address, nextRecvPort++, s.port, this, nextConnectionID++, true);
                        connections.addElement(conn);
                        s.connected = true;
                        break;
                    }                        
                }
            }
            
            ConnectionToRequestMap map;
            TCPPacket tcpPacket;
            while(!receivingComplete) {
                map = connectionToBlockQueue.take();
                tcpPacket = connections.get(map.connection).receivedData.take();
                fos.write(tcpPacket.getData());
                receivingComplete = map.block >= mstcpInformation.fileSize / MSTCPReceiver.blockSize;
                // TODO: Coupled windowing
            }
            
            fos.close();
            
        } catch (Exception e) {
            System.err.println("MSTCPReceiver: Exception Received while Establishing Connection");
            e.printStackTrace();
        }

    }
    
    // Generate requests, mapping block requested to connection sent on
    public synchronized int blockToRequest(int connection) throws InterruptedException {
        if (requestsComplete)
            return -1;
        
        System.out.println("MSTCPReceiver: Requesting block " + nextBlock + " on connnection " + connection);
        connectionToBlockQueue.put(new ConnectionToRequestMap(connection, nextBlock));
        if (nextBlock >= ((mstcpInformation.fileSize / MSTCPReceiver.blockSize)))
            requestsComplete = true;
        return nextBlock++;
    }
    
    
    
    public static void main(String[] args) {
        new MSTCPReceiver("127.0.0.1", 14000, 15000, "./", "hello_repeat.txt"); // recvPort, dstPort
        // new MSTCPReceiver("127.0.0.1", 14000, 15000, "./", "me.jpg"); // recvPort, dstPort
    }
}
