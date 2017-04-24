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
    
    final int recvPort;
    final String path;
    SourceInformation srcInfo;
    Vector<SourceInformation> sources;
    
    HashMap<Integer, MSTCPResponderConnection> connections = new HashMap<Integer, MSTCPResponderConnection>();
    
    MSTCPSocket socket;
    

    
    public MSTCPResponder(String recvAddr, int recvPort, String path, Vector<SourceInformation> sources) {
        this.logger = Utils.getLogger(this.getClass().getName() + "_" + recvPort);
        
        this.recvPort = recvPort;
        this.path = path;
        this.srcInfo = new SourceInformation(recvAddr, recvPort);
        this.sources = sources;

        try {
            logger.info("Starting MSTCPResponder on (" + InetAddress.getByName(srcInfo.address) + ", " + recvPort + ")");
        } catch (UnknownHostException e) {
            logger.log(Level.SEVERE, e.getMessage(), e);
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
                    
                    new MSTCPResponderConnection(inPacket, this, time_req); // handles the connection
                } else {
                    logger.info("Received Unknown/Corrupted Packet");
                }
            }
            
        
        } catch (IOException e) {
            logger.log(Level.SEVERE, e.getMessage(), e);
            System.exit(1);
        }
        
    }
    
    public static void main(String[] args) {
        System.out.println("Note: Setup is for Triangle Network with PC-1 requesting from PC-2 and PC-3");
        System.out.println("Args: either 192.168.2.1 or 192.168.3.1");
        
        final Vector<SourceInformation> sources = new Vector<SourceInformation>();
        sources.add(new SourceInformation("192.168.2.1", 16000));
        sources.add(new SourceInformation("192.168.2.1", 16001));
        sources.add(new SourceInformation("192.168.3.1", 16000));
        sources.add(new SourceInformation("192.168.3.1", 16001));
        
        final String localIP = args[0];
        
        for (SourceInformation s: sources) {
            if (s.address.equals(localIP)) {
                final int localPort = s.port;
                (new Thread() {
                    public void run() {
                        new MSTCPResponder(localIP, localPort, "./", sources);
                    }
                }).start();
            }
        }
    }
}
