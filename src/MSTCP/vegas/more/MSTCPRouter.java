package MSTCP.vegas.more;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

import org.jnetpcap.Pcap;
import org.jnetpcap.PcapIf;
import org.jnetpcap.packet.JRegistry;
import org.jnetpcap.packet.PcapPacket;
import org.jnetpcap.packet.PcapPacketHandler;
import org.jnetpcap.protocol.lan.Ethernet;
import org.jnetpcap.protocol.network.Ip4;
import org.jnetpcap.protocol.tcpip.Udp;

public class MSTCPRouter {
    Logger logger;

    public MSTCPRouter() {
        logger = Utils.getLogger("MSTCPRouter.log");
        StringBuilder errbuf = new StringBuilder();

        List<PcapIf> ifs = new ArrayList<PcapIf>();
        int statusCode = Pcap.findAllDevs(ifs, errbuf);
        if (statusCode != Pcap.OK) {
            logger.severe(errbuf.toString());
            System.exit(1);;
        }
        PcapIf netInterface = ifs.get(6);
        logger.info("Connected to " + netInterface.getName() + " " + netInterface.getDescription());
        
        final Pcap pcap = Pcap.openLive(netInterface.getName(), 2048, Pcap.MODE_PROMISCUOUS, 1, errbuf);
        final int id = JRegistry.mapDLTToId(pcap.datalink());

        PcapPacketHandler<Object> handler = new PcapPacketHandler<Object>() {
            Udp udp = new Udp();
            Ip4 ip = new Ip4();
            Ethernet eth = new Ethernet();

            @Override
            public void nextPacket(PcapPacket packet, Object user) {
                logger.info("Packet Received.");
                packet.scan(id);
                if (packet.hasHeader(udp)) {
                    byte[] payload = udp.getPayload();
                    TCPPacket tcp = new TCPPacket(payload);

                }
                
                pcap.sendPacket(packet);

            }
        };
        
        logger.info("Waiting for Packets.");
        pcap.loop(Pcap.LOOP_INFINITE, handler, null);
        logger.info("We Done Here.");
    }

}
