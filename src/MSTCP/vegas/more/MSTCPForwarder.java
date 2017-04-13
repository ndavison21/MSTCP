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
        final int flowID;
        final NetworkCoder networkCoder;
                
        public FlowData(int flowID, long fileSize, InetAddress reqAddr) {
            this.flowID = flowID;
            this.networkCoder = new NetworkCoder(logger, fileSize, flowID, false);
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
                    if (tcpPacket.isSYN()) {
                        if (tcpPacket.isACK()) { // if SYN+ACK
                            // initialise buffer for innovative packets
                            MSTCPInformation mstcpInfo = new MSTCPInformation(data.getData());
                            if (!flowBuffer.containsKey(mstcpInfo.flowID)) {
                                flowBuffer.put(mstcpInfo.flowID, new FlowData(mstcpInfo.flowID, mstcpInfo.fileSize, data.getAddress()));
                            }
                        }
                    } else if (tcpPacket.isACK()) { // if ACK+Data
                        MOREPacket more = new MOREPacket(tcpPacket.getData());
                        FlowData flow = flowBuffer.get(more.getFlowID());
                        if (flow == null) { // didn't see SYN+ACK, don't have file length so not much we can do
                            logger.warning("Received packet for unitialised flow " + more.getFlowID() + ". Forwarding to next hop.");
                        } else {
                            flow.networkCoder.isInnovative(more); // if innovative store packet and update pre-encoded packet
                            more = flow.networkCoder.getPreEncodedPacket(more.getBatch()); // send pre-encoded packet and pre-encode new packet
                            tcpPacket.setData(more.bytes());
                        }
                    } else if (tcpPacket.isFIN()) { // if FIN
                        MOREPacket more = new MOREPacket(tcpPacket.getData());
                        flowBuffer.remove(more.getFlowID()); // delete buffer
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
