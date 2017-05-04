package MSTCP.reno;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.util.Random;
import java.util.Vector;
import java.util.logging.FileHandler;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

/*
 * VM ARGUMENT: -Djava.util.logging.SimpleFormatter.format="%1$tY-%1$tm-%1$td %1$tH:%1$tM:%1$tS %4$s %2$s %5$s%6$s%n"
 */
public class MSTCPSender {
    Logger logger;
    
    // artificially introducing latency
    Random rand = new Random();
    int latency = 1500;
    boolean delay = true;
    
    SourceInformation srcInfo;
    InetAddress dstAddress;
    int dstPort;
    int recvPort;
    String path;
    boolean transferComplete = false;
    DatagramSocket inSocket, outSocket;
    int initialSeqNum = -1;
    int prevSeqNum = -1;
    int nextSeqNum = -1;
    int winSize;
    
    boolean sent_fin = false;
    
    Vector<SourceInformation> sources;
    MSTCPInformation mstcpInfo;
    
    public byte[] generateTCPPacket(int seqNum, byte[] dataBytes, boolean syn, boolean fin, int time_req) {
        TCPPacket tcpPacket = new TCPPacket(this.recvPort, this.dstPort, seqNum, this.winSize, dataBytes);
        if (syn)
            tcpPacket.setSYN();

        tcpPacket.setACK(seqNum);
        
        tcpPacket.setTime_req(time_req);
        tcpPacket.setTime_ack();
        return tcpPacket.bytes();
    }

    private void delay() throws InterruptedException {
        Thread.sleep(latency + rand.nextInt(300) - 150);
    }
    
    public MSTCPSender(int recvPort, String path, Vector<SourceInformation> sources) {
        logger = Logger.getLogger( MSTCPSender.class.getName() + recvPort );
        try {
            FileHandler handler = new FileHandler("./logs/MSTCPSender_" + recvPort +".log", 1048576, 1, false);
            handler.setFormatter(new SimpleFormatter());
            logger.setUseParentHandlers(false);
            logger.addHandler(handler);
            logger.setLevel(Level.ALL);
        } catch (SecurityException | IOException e1) {
        	System.err.println("MSTCPSender: " + recvPort + ": Unable to Connect to Logger");
            e1.printStackTrace();
            return;
        }
        logger.info("*** NEW RUN ***");
        logger.info(recvPort + ": Starting up MSTCPSender on port " + recvPort);
       
        this.recvPort = recvPort;
        this.path = path;
        this.srcInfo = new SourceInformation("127.0.0.1", recvPort); // TODO: Change to getting public IP not loopback
        this.sources = sources;
        
        
        
        try {
            inSocket = new DatagramSocket(recvPort);
            outSocket = new DatagramSocket();
            
            
            RandomAccessFile raf = null;
            
            logger.info(recvPort + ": Waiting for SYN");
            
            while(initialSeqNum == -1) {
                byte[] synBytes = new byte[1000];
                DatagramPacket syn = new DatagramPacket(synBytes, synBytes.length);
                inSocket.receive(syn);
                if (delay) delay();
                int time_recv = (int) (System.currentTimeMillis() % Integer.MAX_VALUE);
                TCPPacket synPacket = new TCPPacket(synBytes);
                if (synPacket.verifyChecksum() && synPacket.isSYN()) {
                    int seqNum = synPacket.getSeqNum();
                    int time_req = time_recv - synPacket.getTime_req();
                    time_req = time_req < 0 ? (Integer.MAX_VALUE - synPacket.getTime_req()) + time_recv : time_req;
                    logger.info(recvPort + ": SYN Received, initial sequence number " + seqNum + ". Request Latency: " + time_req);
                    initialSeqNum = seqNum;
                    nextSeqNum = initialSeqNum;
                    
                    dstAddress = syn.getAddress(); // when moving to lower level we'll need to get this from IP
                    dstPort = synPacket.getSrcPort();
                    
                    mstcpInfo = new MSTCPInformation(synPacket.getData());
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
                    
                    byte[] ackPkt = generateTCPPacket(nextSeqNum, mstcpInfo.bytes(), true, false, time_req);
                    outSocket.send(new DatagramPacket(ackPkt, ackPkt.length, dstAddress, dstPort));
                }
            }
            
            
            byte[] reqBytes = new byte[MSTCPReceiver.requestSize];
            DatagramPacket req = new DatagramPacket(reqBytes, reqBytes.length);   
            byte[] dataBytes = new byte[MSTCPReceiver.blockSize];
            
            logger.info(recvPort + ": Waiting for Block Requests");
            while(!transferComplete) {
                for (int i=0; i < reqBytes.length; i++)
                    reqBytes[i] = 0; // zero out, stop leftovers from previous iteration
                for (int i=0; i < dataBytes.length; i++)
                    dataBytes[i] = 0; // zero out, stop leftovers from previous iteration
                
                logger.info(recvPort + ": Waiting for next Request.");
                inSocket.receive(req);
                logger.info(recvPort + ": Packet Received.");
                if (delay) delay();
                int time_recv = (int) (System.currentTimeMillis() % Integer.MAX_VALUE);
                TCPPacket reqPacket = new TCPPacket(reqBytes);
                int time_req = time_recv - reqPacket.getTime_req();
                time_req = time_req < 0 ? (Integer.MAX_VALUE - reqPacket.getTime_req()) + time_recv : time_req;
                
                if (reqPacket.verifyChecksum()) {
                    
                    int seqNum = reqPacket.getSeqNum();
                    if (seqNum == nextSeqNum) { // packet received in order, send block
                    	logger.info(recvPort + ": Request " + seqNum + " received in order");

                        int block = ByteBuffer.wrap(reqPacket.getData()).getInt();
                        logger.info(recvPort + ": Block requested " + block);
                        
                        if (sent_fin && reqPacket.isACK()) {
                            logger.info(recvPort + ": We Done Here.");
                            transferComplete = true;
                        } else if (reqPacket.isFIN()) {
                            sent_fin = true;
                            logger.info(recvPort + ": Sending FIN to " + dstAddress + " on port " + dstPort);
                            byte[] ackPkt = generateTCPPacket(nextSeqNum, dataBytes, false, true, time_req);
                            outSocket.send(new DatagramPacket(ackPkt, ackPkt.length, dstAddress, dstPort));
                        } else {
                            raf.seek(block * MSTCPReceiver.blockSize); // find block
                            raf.read(dataBytes);
                            
                            logger.info(recvPort + ": Sending Block " + block + " to " + dstAddress + " on port " + dstPort);
                            byte[] ackPkt = generateTCPPacket(nextSeqNum, dataBytes, false, false, time_req);
                            outSocket.send(new DatagramPacket(ackPkt, ackPkt.length, dstAddress, dstPort));
                            
                            nextSeqNum++;
                            prevSeqNum = seqNum;
                        }
                    } else { // duplicate packet so we send duplicate ACK and Data
                    	logger.info(recvPort + ": Duplicate request " + seqNum + " received");
                        int block = ByteBuffer.wrap(reqPacket.getData()).getInt();
                        raf.seek(block * MSTCPReceiver.blockSize);
                        raf.read(dataBytes);
                        byte[] ackPkt = generateTCPPacket(nextSeqNum, dataBytes, false, false, time_req);
                        outSocket.send(new DatagramPacket(ackPkt, ackPkt.length, dstAddress, dstPort));
                    }
                    
                } else { // packet corrupted so send duplicate ACK
                	logger.info(recvPort + ": Corrupted packet received, sending duplicate ACK " + prevSeqNum);
                    if (initialSeqNum == -1)
                        continue;
                    byte[] ackPkt = generateTCPPacket(prevSeqNum, null, false, false, time_req);
                    outSocket.send(new DatagramPacket(ackPkt, ackPkt.length, dstAddress, dstPort));
                }
            }
            
            logger.info(recvPort + ": Transfer Complete.");
            
            if (raf != null)
                raf.close();
        
        } catch (Exception e) {
            logger.warning(recvPort + ": Exception encountered.");
            logger.log(Level.SEVERE, e.getMessage(), e);
            e.printStackTrace();
            return;
        }
       
    }
    
    public static void main(String[] args) {
        final Vector<SourceInformation> sources = new Vector<SourceInformation>();
        sources.add(new SourceInformation("127.0.0.1", 15000));
        sources.add(new SourceInformation("127.0.0.1", 15001));
        
        (new Thread() {
            public void run() {
                new MSTCPSender(15000, "./", sources);
            }
        }).start();
        
        (new Thread() {
            public void run() {
                new MSTCPSender(15001, "./", sources);
            }
        }).start();
        
        
    }
}
