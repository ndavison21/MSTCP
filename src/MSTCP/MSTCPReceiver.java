package MSTCP;

import java.io.File;
import java.io.FileOutputStream;
import java.util.Vector;
import java.util.concurrent.LinkedBlockingQueue;

public class MSTCPReceiver {
    static int headerSize = 160;  // TCP Header (with no options): 160 bytes
    static int requestSize = 164; // TCP Header: 160 , Request (block to send): 4 bytes
    static int blockSize = 840;   // Send blocks of 840 bytes
    static int pktSize = 1000;    // TCP Header: 160, blocks <= 840, so 1000 Bytes total
    static int synLimit = 50;     // number if times to try SYN before giving up
    
    LinkedBlockingQueue<ConnectionToRequestMap> connectionToBlockQueue;
    Vector<MSTCPReceiverConnection> connections;
    boolean transferComplete = false;
    boolean receivingComplete = false;
    int nextConnectionID = 0;
    
    MSTCPInformation mstcpInformation;
    int nextBlock = 0;
    

    
    
    public MSTCPReceiver(String addr, int recvPort, int dstPort, String path, String filename) {
        System.out.println("MSTCPReceiver: Starting Up MSTCP Connection");
        this.mstcpInformation = new MSTCPInformation(0, filename);
        
        MSTCPReceiverConnection connection = new MSTCPReceiverConnection(addr, recvPort, dstPort, this, nextConnectionID++, false);
        connections.addElement(connection);
        
        try {
            File file = new File(path + "received_" + filename);
            FileOutputStream fos = new FileOutputStream(file);
            
            
            TCPPacket tcpPacket = connections.get(0).receivedData.take();
            mstcpInformation = new MSTCPInformation(tcpPacket.getData());

            ConnectionToRequestMap map;
            while(!receivingComplete) {
                map = connectionToBlockQueue.take();
                tcpPacket = connections.get(map.connection).receivedData.take();
                fos.write(tcpPacket.getData());
                receivingComplete = map.block * MSTCPReceiver.blockSize >= mstcpInformation.fileSize;
                    
            }
            fos.close();
        } catch (Exception e) {
            System.err.println("MSTCPReceiver: Exception Received while Establishing Connection");
            e.printStackTrace();
        }

    }
    
    // Generate requests, mapping block requested to connection sent on
    public synchronized int blockToRequest(int connection) throws InterruptedException {
        if (nextBlock * MSTCPReceiver.blockSize < mstcpInformation.fileSize) {
            connectionToBlockQueue.put(new ConnectionToRequestMap(connection, nextBlock));
            return nextBlock++;
        }
        return -1;
    }
    
    
    
    public static void main(String[] args) {
        new MSTCPReceiver("127.0.0.1", 14415, 14416, "./", "hello_repeat.txt"); // recvPort, dstPort
    }
}
