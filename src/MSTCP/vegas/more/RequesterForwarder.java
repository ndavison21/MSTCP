package MSTCP.vegas.more;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.HashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

public class RequesterForwarder {
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
    
    public RequesterForwarder(int recvPort) throws SocketException {
        this.logger = Utils.getLogger(this.getClass().getName() + "_" + recvPort);
        this.socket = new MSTCPSocket(logger, recvPort);
        try {
            this.localhost = InetAddress.getLocalHost();
        } catch (UnknownHostException e) {
            logger.log(Level.SEVERE, e.getMessage(), e);
            System.exit(1);
        }
        
        DatagramPacket data;
        try {
        
            for (;;) {
                data = socket.receive(); // TODO: get from pcap
                TCPPacket tcpPacket = new TCPPacket(data.getData());
                InetAddress destAddr = data.getAddress(); // TODO: get from IP layer

                int nextPort;
                if (tcpPacket.verifyChecksum()) { // if it's corrupted we may as well just drop now
                    if (MOREPacket.getPacketType(tcpPacket.getData()) == MOREPacket.RETURN_PACKET) { // only interested in return packets
                        nextPort = tcpPacket.getDestPort();
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
                        switch (tcpPacket.getDestPort()) { // TODO: delay and drop
                            case 16000:
                                nextPort = 15001;
                                break;
                            case 16001:
                                nextPort = 15002;
                                break;
                            case 16002:
                                nextPort = 15003;
                                break;
                            case 16003:
                                nextPort = 15004;
                                break;
                            default:
                                nextPort = tcpPacket.getDestPort();
                        }
                        
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
            System.exit(1);
        }
    }
}
