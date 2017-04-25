package MSTCP.vegas.more;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.jnetpcap.Pcap;
import org.jnetpcap.PcapIf;
import org.jnetpcap.packet.JRegistry;
import org.jnetpcap.packet.PcapPacket;
import org.jnetpcap.packet.PcapPacketHandler;
import org.jnetpcap.protocol.network.Ip4;
import org.jnetpcap.protocol.tcpip.Udp;

/**
 * NOTE: THIS CLASS IS HERE FOR REFERENCE FOR FUTURE DEVELOPMENT AND WILL NOT WORK AS DESIRED IN ITS CURRENT FORM.
 * 
 *  The router running this must drop UDP packets
 *      "iptables -A FORWARD -p udp -j DROP"
 *  Can also try and filter traffic from/to specific ports reserved for MSTCP e.g. 14000-14020, 16000-16020
 *      "iptables -A FORWARD -p udp --sport 14000:14020 --dport 15000:15020 -j DROP"
 *
 */
public class MSTCPRouter {
    final Logger logger;
    
    final MSTCPSocket socket; // socket for forwarding packets
    final HashMap<Integer, FlowData> flowBuffer = new HashMap<Integer, FlowData>(); // map flowIDs to flow data
    
    private class FlowData {
        // final int flowID;
        final NetworkCoder networkCoder;
        
        public FlowData(int flowID, long fileSize, InetAddress reqAddr) {
            // this.flowID = flowID;
            this.networkCoder = new NetworkCoder(logger, fileSize, flowID, false);
        }
    }

    public MSTCPRouter(int interfaceIndex) throws SocketException {
        this.logger = Utils.getLogger(this.getClass().getName());
        this.socket = new MSTCPSocket(logger);
        StringBuilder errbuf = new StringBuilder();

        // get network interfaces
        List<PcapIf> ifs = new ArrayList<PcapIf>();
        int statusCode = Pcap.findAllDevs(ifs, errbuf);
        if (statusCode != Pcap.OK) {
            logger.severe(errbuf.toString());
            System.exit(1);;
        }
        PcapIf netInterface = ifs.get(interfaceIndex);
        logger.info("Connected to " + netInterface.getName() + " " + netInterface.getDescription());
        
        // open network interface
        final Pcap pcap = Pcap.openLive(netInterface.getName(), 2048, Pcap.MODE_PROMISCUOUS, 1, errbuf);
        final int id = JRegistry.mapDLTToId(pcap.datalink());

        PcapPacketHandler<Object> handler = new PcapPacketHandler<Object>() {
            Udp udp = new Udp();
            Ip4 ip = new Ip4();

            @Override
            public void nextPacket(PcapPacket packet, Object user) {
                packet.scan(id);
                if (packet.hasHeader(ip)) {
                    if (packet.hasHeader(udp)) { // we send MSTCP over UDP
                        try {
                        
                            InetAddress addr = InetAddress.getByAddress(ip.destination());
                            int port = udp.destination();
                            
                            byte[] payload = udp.getPayload();
                            TCPPacket tcpPacket = new TCPPacket(payload);
                            
                            if (tcpPacket.getSeqNum() != -1 && tcpPacket.verifyChecksum()) { // if false then either packet is not a TCPPacket or it is corrupted. Cannot distinguish.
                                port = tcpPacket.getDestPort();
                                if (tcpPacket.isSYN()) {
                                    if (tcpPacket.isACK()) { // if SYN+ACK
                                        // initialise buffer for innovative packets
                                        MSTCPInformation mstcpInfo = new MSTCPInformation(tcpPacket.getData());
                                        logger.info("Received SYN+ACK for flow " + mstcpInfo.flowID);
                                        if (!flowBuffer.containsKey(mstcpInfo.flowID)) {
                                            flowBuffer.put(mstcpInfo.flowID, new FlowData(mstcpInfo.flowID, mstcpInfo.fileSize, addr));
                                        }
                                    } else 
                                        logger.info("Received MSTCP SYN. Waiting for SYN+ACK");
                                } else if (tcpPacket.isACK()) { // if ACK+Data
                                    MOREPacket more = new MOREPacket(tcpPacket.getData());
                                    FlowData flow = flowBuffer.get(more.getFlowID());
                                    if (flow == null) { // didn't see SYN+ACK, don't have file length so not much we can do
                                        logger.warning("Received ACK+Data for unitialised flow " + more.getFlowID() + ". Forwarding to next hop.");
                                    } else {
                                        logger.info("Received ACK+Data for flow " + more.getFlowID());
                                        flow.networkCoder.isInnovative(more); // if innovative store packet and update pre-encoded packet
                                        more = flow.networkCoder.getPreEncodedPacket(more.getBatch()); // send pre-encoded packet and pre-encode new packet
                                        tcpPacket.setData(more.bytes());
                                        payload = tcpPacket.bytes();
                                    }
                                } else if (tcpPacket.isFIN()) { // if FIN
                                    MOREPacket more = new MOREPacket(tcpPacket.getData());
                                    logger.info("Received FIN for flow " + more.getFlowID());
                                    flowBuffer.remove(more.getFlowID()); // delete buffer
                                }
                            } else
                                logger.info("Received unknown packet.");
                            
                            
                            // pcap.sendPacket(packet); // not supported on unix
                            socket.send(new DatagramPacket(payload, payload.length, addr, port));
                        } catch (IOException e) {
                            logger.log(Level.SEVERE, e.getMessage(), e);
                            System.exit(1);
                        }
                    }
                }
                
                

            }
        };
        
        logger.info("Waiting for Packets.");
        pcap.loop(Pcap.LOOP_INFINITE, handler, null);
        logger.info("We Done Here.");
    }
    
    public static void main(String[] args) throws SocketException {
        System.out.println("Args: index of the interface connecting to (check using DetectInterfaces.java)");
        System.out.println("Note: drop udp forward packets packets. E.g. \"iptables -A FORWARD -p udp -j DROP\" ");
        new MSTCPRouter(Integer.parseInt(args[0]));
    }

}
