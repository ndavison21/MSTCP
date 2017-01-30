package MSTCP;

import java.util.Vector;

public class MSTCPReceiver {
    static int headerSize = 160;  // TCP Header (with no options): 160 bytes
    static int requestSize = 164; // TCP Header: 160 , Request (block to send): 4 bytes
    static int blockSize = 840;   // Send blocks of 840 bytes
    static int pktSize = 1000;    // TCP Header: 160, blocks <= 840, so 1000 Bytes total
    static int synLimit = 50;     // number if times to try SYN before giving up
    
    Vector<MSTCPReceiverConnection> connections;
    boolean transferComplete = false;
    int next_id = 0;
    
    
    public MSTCPReceiver(String addr, int recvPort, int dstPort, String path) {
        System.out.println("MSTCPReceiver: Starting Up MSTCP Connection");
        
        MSTCPReceiverConnection connection = new MSTCPReceiverConnection(addr, recvPort, dstPort, false, this, next_id++);
        connections.addElement(connection);
        
        while (!transferComplete) {
            
        }
    }
    
    // Generate requests, mapping block requested to connection sent on
    public synchronized int blockToRequest(int connection) {
        // TODO
        return -1;
    }
    
    
    
    public static void main(String[] args) {
        new MSTCPReceiver("127.0.0.1", 14415, 14416, "./"); // recvPort, dstPort
    }
}
