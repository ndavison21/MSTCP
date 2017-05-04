package MSTCP.vegas.more;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.HashMap;
import java.util.Vector;
import java.util.logging.Level;
import java.util.logging.Logger;

public class MSTCPResponder {
    final Logger logger;
    
    final String recvAddr;
    final int recvPort;
    final int routerPort;
    final String path;
    // SourceInformation srcInfo;
    Vector<SourceInformation> sources;
    
    HashMap<Integer, MSTCPResponderConnection> connections = new HashMap<Integer, MSTCPResponderConnection>();
    
    MSTCPSocket socket;
    
    public MSTCPResponder(String recvAddr, int recvPort, String path, Vector<SourceInformation> sources) {
        this(recvAddr, recvPort, recvPort, path, sources);
    }
    
    public MSTCPResponder(String recvAddr, int recvPort, int routerPort, String path, Vector<SourceInformation> sources) {
        this.logger = Utils.getLogger(this.getClass().getName() + "_" + recvPort);
        
        this.recvAddr = recvAddr;
        this.recvPort = recvPort;
        this.routerPort = routerPort;
        this.path = path;
        this.sources = sources;

        try {
            logger.info("Starting MSTCPResponder on (" + InetAddress.getByName(recvAddr) + ", " + recvPort + ")");
        } catch (UnknownHostException e) {
            logger.log(Level.SEVERE, e.getMessage(), e);
            e.printStackTrace();
            System.exit(1);
        }
        
        try {
            socket = new MSTCPSocket(logger, recvPort);
            
            DatagramPacket udpPkt;
            TCPPacket inPacket;
            int time_recv, time_req;
        
            for (;;) {
                logger.info("Waiting for SYN");
                udpPkt = socket.receive();
                
                time_recv = (int) (System.currentTimeMillis() % Integer.MAX_VALUE);
                inPacket = new TCPPacket(udpPkt.getData());
                
                if (inPacket.verifyChecksum() && inPacket.isSYN()) {
                    time_req = time_recv - inPacket.getTime_req();
                    if (time_req < 0)
                        time_req = Integer.MAX_VALUE - inPacket.getTime_req() + time_recv;
                    logger.info("SYN Received. Initial Sequence Number " + inPacket.getSeqNum() + ". Request Latency " + time_req);
                    
                    new MSTCPResponderConnection(inPacket, this, time_req, recvPort); // handles the connection
                } else {
                    logger.info("Received Unknown/Corrupted Packet");
                }
            }
            
        
        } catch (IOException e) {
            logger.log(Level.SEVERE, e.getMessage(), e);
            e.printStackTrace();
            System.exit(1);
        }
        
    }
    
}
