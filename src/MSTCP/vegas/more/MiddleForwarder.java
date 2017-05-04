package MSTCP.vegas.more;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.HashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

public class MiddleForwarder {
    final Logger logger;
    
    final MSTCPSocket socket; // socket for receiving and forwarding packets. TODO: intercept packets using pcap
    final HashMap<Integer, FlowData> flowBuffer = new HashMap<Integer, FlowData>(); // map flowIDs to the flow buffer
    
    InetAddress localhost; // used for network emulation, in practice get destination address from IP layer
    
    private class FlowData { // class to store data and functionalilty for flow
        // final int flowID;
        final NetworkCoder networkCoder;
                
        public FlowData(int flowID, long fileSize) {
            // this.flowID = flowID;
            this.networkCoder = new NetworkCoder(logger, fileSize);
        }
    }
    
    int count = -1;
    
    public MiddleForwarder(int recvPort) throws SocketException {
        this(recvPort, 0, 0);
    }
    
    public MiddleForwarder(int recvPort, int delay, double p_drop) throws SocketException {
        this.logger = Utils.getLogger(this.getClass().getName() + "_" + recvPort);
        this.socket = new MSTCPSocket(logger, recvPort, delay, p_drop);
        try {
            this.localhost = InetAddress.getLocalHost();
        } catch (UnknownHostException e) {
            logger.log(Level.SEVERE, e.getMessage(), e);
            e.printStackTrace();
            System.exit(1);
        }
        
        logger.info("Started MiddleForwarder on port " + recvPort + " delay " + delay + " p_drop " + p_drop);
        
        DatagramPacket data;
        try {
        
            for (;;) {
                count++;
                data = socket.receive(); // TODO: get from pcap
                TCPPacket tcpPacket = new TCPPacket(data.getData());
                InetAddress destAddr = data.getAddress(); // TODO: get from IP layer

                int nextPort;
                if (tcpPacket.verifyChecksum()) { // if it's corrupted we may as well just drop now
                    switch (tcpPacket.getDestPort()) {
                        case 14000:
                            nextPort = 15000;
                            break;
                        case 14001:
                            nextPort = 15000;
                            break;
                        case 14002:
                            nextPort = 15000;
                            break;
                        case 14003:
                            nextPort = 15000;
                            break;
                        case 16000:
                            nextPort = 15005;
                            break;
                        case 16001:
                            nextPort = 15005;
                            break;
                        case 16002:
                            nextPort = 15006;
                            break;
                        case 16003:
                            nextPort = 15006;
                            break;
                        default:
                            nextPort = 15000;
                    }
                    if (MOREPacket.getPacketType(tcpPacket.getData()) == MOREPacket.RETURN_PACKET) { // only interested in return packets
                        logger.info("Received Return Packet.");
                        if (tcpPacket.isACK()) {
                            if (tcpPacket.isFIN()) { // if FIN+ACK
                                flowBuffer.remove(MOREPacket.getFlowID(tcpPacket.getData())); // delete buffer
                            } else { // if ACK+Data
                                
                                MOREPacket more = new MOREPacket(tcpPacket.getData());
                                FlowData flow = flowBuffer.get(more.getFlowID());
                                if (flow == null) { // didn't see SYN+ACK, don't have file length so not much we can do
                                    //logger.warning("Forwarding packet for unitialised flow " + more.getFlowID() + ".");
                                } else {
                                    if (Utils.recode)
                                        more = flow.networkCoder.processPacket(more); // if innovative store packet and update pre-encoded packet
                                    tcpPacket.setData(more.bytes());
                                }
                                
                            }
                        }
                    } else {
                        logger.info("Received Forward Packet.");
                        
                        if (tcpPacket.isSYN() && tcpPacket.isACK()) { // if SYN+ACK
                            // initialise buffer for innovative packets
                            int flowID = MSTCPInformation.getFlowID(tcpPacket.getData());
                            long fileSize = MSTCPInformation.getFileSize(tcpPacket.getData());
                            logger.info("Received SYN+ACK for flow " + flowID + ". Initialising.");
                            if (!flowBuffer.containsKey(flowID))
                                flowBuffer.put(flowID, new FlowData(flowID, fileSize));
                        }
                    }
                    byte[] tcpBytes = tcpPacket.bytes();
                    socket.send(new DatagramPacket(tcpBytes, tcpBytes.length, destAddr, nextPort));
                } else
                    logger.info("Dropping Corrupted Packet.");
            }
        } catch (IOException e) {
            logger.log(Level.SEVERE, e.getMessage(), e);
            e.printStackTrace();
            System.exit(1);
        }
    }
}
