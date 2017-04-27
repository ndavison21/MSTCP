package MSTCP.vegas.more;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.HashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

public class MSTCPForwarder {
    final Logger logger;
    
    final MSTCPSocket socket; // socket for receiving and forwarding packets. TODO: intercept packets using pcap
    final HashMap<Integer, FlowData> flowBuffer = new HashMap<Integer, FlowData>(); // map flowIDs to the flow buffer
    
    InetAddress localhost; // used for network emulation, in practice get destination address from IP layer
    
    private class FlowData { // class to store data and functionalilty for flow
        // final int flowID;
        final NetworkCoder_2 networkCoder;
                
        public FlowData(int flowID, long fileSize) {
            // this.flowID = flowID;
            this.networkCoder = new NetworkCoder_2(logger, fileSize);
        }
    }
    
    public MSTCPForwarder(int recvPort) throws SocketException {
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
                
                if (tcpPacket.verifyChecksum()) { // if it's corrupted we may as well just drop now
                    if (tcpPacket.isSYN() && tcpPacket.isACK()) { // if SYN+ACK
                        // initialise buffer for innovative packets
                        int flowID = MSTCPInformation.getFlowID(tcpPacket.getData());
                        long fileSize = MSTCPInformation.getFileSize(tcpPacket.getData());
                        logger.info("Received SYN+ACK for flow " + flowID + ". Initialising.");
                        if (!flowBuffer.containsKey(flowID)) {
                            flowBuffer.put(flowID, new FlowData(flowID, fileSize));
                        }
                    } else if (MOREPacket.getPacketType(tcpPacket.getData()) == MOREPacket.RETURN_PACKET) { // only interested in return packets
                        if (tcpPacket.isACK()) {
                            if (tcpPacket.isFIN()) { // if FIN+ACK
                                flowBuffer.remove(MOREPacket.getFlowID(tcpPacket.getData())); // delete buffer
                            } else { // if ACK+Data
                                
                                MOREPacket more = new MOREPacket(tcpPacket.getData());
                                FlowData flow = flowBuffer.get(more.getFlowID());
                                if (flow == null) { // didn't see SYN+ACK, don't have file length so not much we can do
                                    logger.warning("Received packet for unitialised flow " + more.getFlowID() + ". Forwarding to next hop.");
                                } else {
                                    more = flow.networkCoder.processPacket(more); // if innovative store packet and update pre-encoded packet
                                    tcpPacket.setData(more.bytes());
                                }
                                
                            }
                        }
                    }

                    
                    byte[] tcpBytes = tcpPacket.bytes();
                    socket.send(new DatagramPacket(tcpBytes, tcpBytes.length, destAddr, tcpPacket.getDestPort()));
                } else
                    logger.info("Dropping Corrupted Packet.");
            }
        } catch (IOException e) {
            logger.log(Level.SEVERE, e.getMessage(), e);
            System.exit(1);
        }
    }
}
