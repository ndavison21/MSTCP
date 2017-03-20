package MSTCP.vegas;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.util.LinkedList;
import java.util.Vector;
import java.util.logging.Level;
import java.util.logging.Logger;

public class MSTCPResponder {
    final Logger logger;
    
    int initialSeqNum;
    int nextSeqNum;
    int dstPort;
    InetAddress dstAddress;
    int cwnd;
    LinkedList<Integer> toAck = new LinkedList<Integer>();
    
    final int recvPort;
    final String path;
    SourceInformation srcInfo;
    MSTCPInformation mstcpInfo;
    Vector<SourceInformation> sources;
    boolean connected = false;
    
    DatagramSocket inSocket, outSocket;
    
    public byte[] generateTCPPacket(int seqNum, byte[] dataBytes, boolean syn, boolean fin, int time_req) {
        TCPPacket tcpPacket = new TCPPacket(recvPort, dstPort, seqNum, cwnd, dataBytes);
        if (syn)
            tcpPacket.setSYN();

        tcpPacket.setACK(toAck.isEmpty() ? nextSeqNum : toAck.removeFirst());
        
        tcpPacket.setTime_req(time_req);
        tcpPacket.setTime_ack();
        return tcpPacket.bytes();
    }
    
    public MSTCPResponder(int recvPort, String path, Vector<SourceInformation> sources) {
        this.logger = Utils.getLogger(this.getClass().getName() + "_" + recvPort);
        
        this.recvPort = recvPort;
        this.path = path;
        this.srcInfo = new SourceInformation(Utils.getIPAddress(logger), recvPort);
        this.sources = sources;

        logger.info("Starting MSTCPResponder on (" + srcInfo.address + ", " + recvPort + ")");
        
        try {
            inSocket = new DatagramSocket(recvPort);
            outSocket = new DatagramSocket();
    
            RandomAccessFile raf = null;
            
            byte[] inBytes = new byte[Utils.requestSize], outBytes;
            DatagramPacket udpPkt = new DatagramPacket(inBytes, inBytes.length);
            TCPPacket inPacket;
            int time_recv, time_req;
        
            for (;;) {
                inSocket.receive(udpPkt);
                if (Utils.delayAndDrop(logger))
                    continue;
                
                time_recv = (int) (System.currentTimeMillis() % Integer.MAX_VALUE);
                inPacket = new TCPPacket(inBytes);
                
                if (inPacket.verifyChecksum()) {
                    if (inPacket.isACK() && inPacket.getSeqNum() == initialSeqNum)
                        break;
                    if (inPacket.isSYN()) {
                        time_req = time_recv - inPacket.getTime_req();
                        if (time_req < 0)
                            time_req = Integer.MAX_VALUE - inPacket.getTime_req() + time_recv;
                        logger.info("SYN Received. Initial Sequence Number " + inPacket.getSeqNum() + ". Request Latency " + time_req);
                        initialSeqNum = inPacket.getSeqNum();
                        nextSeqNum = initialSeqNum;
                        dstAddress = udpPkt.getAddress(); // when moving to lower level we'll need to get this from IP
                        dstPort = inPacket.getSrcPort();
                        
                        mstcpInfo = new MSTCPInformation(inPacket.getData());
                        mstcpInfo.sources = this.sources;
                        for (SourceInformation s: mstcpInfo.sources) { // set this sender to connected
                            if (s.address == srcInfo.address && s.port == srcInfo.port) {
                                s.connected = true;
                                srcInfo.connected = true;
                            }
                        }
                        
                        File file = new File(path + mstcpInfo.filename); // open file TODO: handle case where file can't be found
                        raf = new RandomAccessFile(file, "r"); // RandomAccessFile so we can seek specific blocks
                        
                        mstcpInfo.fileSize = file.length();
                        
                        outBytes = generateTCPPacket(initialSeqNum, null, true, false, time_req);
                        outSocket.send(new DatagramPacket(outBytes, outBytes.length, dstAddress, dstPort));
                    }
                }
            }
            
            byte[] dataBytes;
            
            for (;;) {
                dataBytes = new byte[Utils.blockSize];
                if (connected) {
                    for (int i=0; i<inBytes.length; i++)
                        inBytes[i] = 0;
                    inSocket.receive(udpPkt);
                    if (Utils.delayAndDrop(logger))
                        continue;
                    time_recv = (int) (System.currentTimeMillis() % Integer.MAX_VALUE);
                    inPacket = new TCPPacket(inBytes);
                } else
                    connected = true;
                
                if (inPacket.verifyChecksum()) {
                    if (inPacket.getSeqNum() == nextSeqNum)
                        nextSeqNum++;
                    else if (inPacket.getSeqNum() > nextSeqNum){
                        toAck.add(inPacket.getSeqNum());
                        nextSeqNum = inPacket.getSeqNum() + 1;
                    }
                    
                    time_req = time_recv - inPacket.getTime_req();
                    if (time_req < 0)
                        time_req = (Integer.MAX_VALUE - inPacket.getTime_req()) + time_recv;
                        
                    
                    int block = ByteBuffer.wrap(inPacket.getData()).getInt();
                    logger.info("Received packet " + inPacket.getSeqNum() + " requesting block " + block);
                    raf.seek(block * Utils.blockSize);
                    raf.read(dataBytes);
                    outBytes = generateTCPPacket(inPacket.getSeqNum(), dataBytes, false, false, time_req);
                }
                

            }
        
        } catch (IOException e) {
            logger.log(Level.SEVERE, e.getMessage(), e);
            System.exit(1);
        }
        
    }
}
