package MSTCP.vegas.more;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.HashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

public class ResponderForwarder {
    final Logger logger;
    
    final MSTCPSocket socket; // socket for receiving and forwarding packets. TODO: intercept packets using pcap
    final HashMap<Integer, FlowData> flowBuffer = new HashMap<Integer, FlowData>(); // map flowIDs to the flow buffer
    
    InetAddress localhost; // used for network emulation, in practice get destination address from IP layer
    
    int packets = 0;
    final int packetLimit;
    final int throttleLimit;
    
    private class FlowData { // class to store data and functionalilty for flow
        // final int flowID;
        final NetworkCoder networkCoder;
                
        public FlowData(int flowID, long fileSize) {
            // this.flowID = flowID;
            this.networkCoder = new NetworkCoder(logger, fileSize);
        }
    }
    
    public ResponderForwarder(int recvPort) throws SocketException {
        this(recvPort, Integer.MAX_VALUE, Integer.MAX_VALUE);
    }
    
    public ResponderForwarder(int recvPort, int packetLimit, int throttleLimit) throws SocketException {
        this.logger = Utils.getLogger(this.getClass().getName() + "_" + recvPort);
        this.socket = new MSTCPSocket(logger, recvPort, 5, 0);
        this.packetLimit = packetLimit;
        this.throttleLimit = throttleLimit;
        try {
            this.localhost = InetAddress.getLocalHost();
        } catch (UnknownHostException e) {
            logger.log(Level.SEVERE, e.getMessage(), e);
            e.printStackTrace();
            System.exit(1);
        }
        
        logger.info("Started ResponderForwarder on port " + recvPort + " packet limit " + packetLimit);
        
        DatagramPacket data;
        try {
            boolean logged = false;
            for (;;) {
                data = socket.receive();
                
                if (packets > packetLimit) {
                    logger.info("Received more than " + packetLimit + " packets. Not processing any more.");
                    if (!logged) {
                        System.out.println("Packet Limit Reached. Dropping any further packets.");
                        logged = true;
                    }
                    continue;
                } else if (packets > throttleLimit) {
                    socket.delay += 10;
                    socket.p_drop *= 2;
                } else {
                    logger.info("Received " + packets + " packets");
                }
                TCPPacket tcpPacket = new TCPPacket(data.getData());
                InetAddress destAddr = data.getAddress(); // TODO: get from IP layer

                int nextPort;
                if (tcpPacket.verifyChecksum()) { // if it's corrupted we may as well just drop now                        
                    switch (tcpPacket.getDestPort()) { // TODO: delay and drop
                        case 14000:
                            nextPort = 15001;
                            break;
                        case 14001:
                            nextPort = 15002;
                            break;
                        case 14002:
                            nextPort = 15003;
                            break;
                        case 14003:
                            nextPort = 15004;
                            break;
                        case 16000:
                            nextPort = 16000;
                            break;
                        case 16001:
                            nextPort = 16001;
                            break;
                        case 16002:
                            nextPort = 16002;
                            break;
                        case 16003:
                            nextPort = 16003;
                            break;
                        default:
                            nextPort = tcpPacket.getDestPort();
                    }
                    if (MOREPacket.getPacketType(tcpPacket.getData()) == MOREPacket.RETURN_PACKET) { // only interested in return packets

                        if (tcpPacket.isACK()) {
                            if (tcpPacket.isFIN()) { // if FIN+ACK
                                flowBuffer.remove(MOREPacket.getFlowID(tcpPacket.getData())); // delete buffer
                            } else { // if ACK+Data
                                
                                MOREPacket more = new MOREPacket(tcpPacket.getData());
                                FlowData flow = flowBuffer.get(more.getFlowID());
                                if (flow == null) { // didn't see SYN+ACK, don't have file length so not much we can do
                                    logger.warning("Received packet for unitialised flow " + more.getFlowID() + ". Forwarding to next hop.");
                                } else {
                                    if (Utils.recode)
                                        more = flow.networkCoder.processPacket(more); // if innovative store packet and update pre-encoded packet
                                    tcpPacket.setData(more.bytes());
                                }
                                
                            }
                        }
                    } else {
                        packets++;
                        
                        if (tcpPacket.isSYN() && tcpPacket.isACK()) { // if SYN+ACK
                            // initialise buffer for innovative packets
                            int flowID = MSTCPInformation.getFlowID(tcpPacket.getData());
                            long fileSize = MSTCPInformation.getFileSize(tcpPacket.getData());
                            logger.info("Received SYN+ACK for flow " + flowID + ". Initialising.");
                            if (!flowBuffer.containsKey(flowID))
                                flowBuffer.put(flowID, new FlowData(flowID, fileSize));
                        } else {
                            logger.info("Received Packet. Forwarding to " + nextPort);
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
