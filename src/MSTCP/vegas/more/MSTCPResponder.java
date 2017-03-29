package MSTCP.vegas.more;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.LinkedList;
import java.util.Timer;
import java.util.TimerTask;
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
    boolean sentFINACK = false;
    Timer timer;
    
    MSTCPSocket socket;
    
    private void stopTimer() {
        if (timer != null)
            timer.cancel();
    }
    
    private void setTimer() {
        stopTimer();
        timer = new Timer();
        timer.schedule(new FINACKTimeout(), Utils.finTimeout);
    }
    
    private class FINACKTimeout extends TimerTask {
        public void run() {
            logger.info("Timeout after sending FIN + ACK. Closing Connection to (" + dstAddress + ", " + dstPort + ")");
            TCPPacket fin = new TCPPacket(recvPort, dstPort, nextSeqNum, cwnd);
            fin.setFIN();
            fin.setACK(nextSeqNum);
            byte[] finBytes = fin.bytes();
            try {
                socket.send(new DatagramPacket(finBytes, finBytes.length, InetAddress.getByName(srcInfo.address), srcInfo.port));
            } catch (IOException e) {
                logger.log(Level.SEVERE, e.getMessage(), e);
                System.exit(1);
            }
        }
    }
    
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

        try {
            logger.info("Starting MSTCPResponder on (" + InetAddress.getByName(srcInfo.address) + ", " + recvPort + ")");
        } catch (UnknownHostException e) {
            logger.log(Level.SEVERE, e.getMessage(), e);
            System.exit(1);
        }
        
        try {
            socket = new MSTCPSocket(logger, recvPort);
    
            RandomAccessFile raf = null;
            
            byte[] outBytes;
            DatagramPacket udpPkt;
            TCPPacket inPacket;
            int time_recv, time_req;
        
            for (;;) {
                udpPkt = socket.receive();
                
                time_recv = (int) (System.currentTimeMillis() % Integer.MAX_VALUE);
                inPacket = new TCPPacket(udpPkt.getData());
                
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
                        
                        mstcpInfo = new MSTCPInformation(inPacket.getData());
                        mstcpInfo.sources = this.sources;
                        dstAddress = InetAddress.getByAddress(mstcpInfo.recvAddr); // when moving to lower level we'll need to get this from IP
                        dstPort = mstcpInfo.recvPort;
                        for (SourceInformation s: mstcpInfo.sources) { // set this sender to connected
                            if (s.address == srcInfo.address && s.port == srcInfo.port) {
                                s.connected = true;
                                srcInfo.connected = true;
                            }
                        }
                        
                        File file = new File(path + mstcpInfo.filename); // open file TODO: handle case where file can't be found
                        raf = new RandomAccessFile(file, "r"); // RandomAccessFile so we can seek specific blocks
                        
                        mstcpInfo.fileSize = file.length();
                        
                        logger.info("Sending SYN + ACK to (" + dstAddress + ", " + dstPort + ")");
                        outBytes = generateTCPPacket(initialSeqNum, mstcpInfo.bytes(), true, false, time_req);
                        socket.send(new DatagramPacket(outBytes, outBytes.length, dstAddress, dstPort));
                    }
                } else {
                    logger.info("Received Corrupted Packet");
                }
            }
            
            byte[] dataBytes;
            
            for (;;) {
                dataBytes = new byte[Utils.blockSize];
                if (connected) {
                    udpPkt = socket.receive();
                    time_recv = (int) (System.currentTimeMillis() % Integer.MAX_VALUE);
                    inPacket = new TCPPacket(udpPkt.getData());
                } else
                    connected = true;
                
                if (inPacket.verifyChecksum()) {
                    time_req = time_recv - inPacket.getTime_req();
                    if (time_req < 0)
                        time_req = (Integer.MAX_VALUE - inPacket.getTime_req()) + time_recv;
                    
                    if (inPacket.isFIN() && !sentFINACK) {
                        logger.info("Received FIN packet " + inPacket.getSeqNum() + ". Sending FIN + ACK to (" + dstAddress + ", " + dstPort + ")");
                        outBytes = generateTCPPacket(inPacket.getSeqNum(), null, false, true, time_req);
                        sentFINACK = true;
                        setTimer();
                    } else if (inPacket.isACK() && sentFINACK) {
                        logger.info("Received ACK after sending FIN + ACK. Closing Connection to (" + dstAddress + ", " + dstPort + ")");
                        stopTimer();
                        break;
                    } else {
                        if (inPacket.getSeqNum() == nextSeqNum)
                            nextSeqNum++;
                        else if (inPacket.getSeqNum() > nextSeqNum){
                            toAck.add(inPacket.getSeqNum());
                            nextSeqNum = inPacket.getSeqNum() + 1;
                        }
                        
                        MOREPacket more = new MOREPacket(inPacket.getData());
                        short block = more.getCodeVector()[0];
                        logger.info("Received packet " + inPacket.getSeqNum() + " requesting block " + block);
                        raf.seek(block * Utils.blockSize);
                        raf.read(dataBytes);
                        more.setPacketType((short) 1); 
                        more.setEncodedData(dataBytes);
                        outBytes = generateTCPPacket(inPacket.getSeqNum(), more.bytes(), false, false, time_req);
                        logger.info("Sending ACK + Block to (" + dstAddress + ", " + dstPort + ")");
                    }
                    
                    socket.send(new DatagramPacket(outBytes, outBytes.length, dstAddress, dstPort));
                } else {
                    logger.info("Received Corrupted Packet");
                }
                

            }
            
            socket.close();
            raf.close();
            
            logger.info("We Done Here.");
        
        } catch (IOException e) {
            logger.log(Level.SEVERE, e.getMessage(), e);
            System.exit(1);
        }
        
    }
}
