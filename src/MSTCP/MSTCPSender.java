package MSTCP;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.ByteBuffer;
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
    
    Vector<SourceInformation> sources;
    MSTCPInformation mstcpInfo;
    
    public byte[] generateTCPPacket(int seqNum, byte[] dataBytes, boolean syn, boolean fin) {
        TCPPacket tcpPacket = new TCPPacket(this.recvPort, this.dstPort, seqNum, this.winSize, dataBytes);
        if (syn)
            tcpPacket.setSYN();

        tcpPacket.setACK(seqNum);
        return tcpPacket.bytes();
    }
    
    public MSTCPSender(int recvPort, String path, Vector<SourceInformation> sources) {
        logger = Logger.getLogger( MSTCPSender.class.getName() + recvPort );
        try {
            FileHandler handler = new FileHandler("./logs/MSTCPSender_" + recvPort +".log", 8096, 1, true);
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
                TCPPacket synPacket = new TCPPacket(synBytes);
                if (synPacket.verifyChecksum() && synPacket.isSYN()) {
                    int seqNum = synPacket.getSeqNum();
                    logger.info(recvPort + ": SYN Received, initial sequence number " + seqNum);
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
                    
                    byte[] ackPkt = generateTCPPacket(nextSeqNum, mstcpInfo.bytes(), true, false);
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
                
                
                inSocket.receive(req);
                TCPPacket reqPacket = new TCPPacket(reqBytes);
                
                if (reqPacket.verifyChecksum()) {
                    int seqNum = reqPacket.getSeqNum();
                    if (seqNum == nextSeqNum) { // packet received in order, send block
                    	logger.info(recvPort + ": Request " + seqNum + " received in order");
                        int block = ByteBuffer.wrap(reqPacket.getData()).getInt();
                        logger.info(recvPort + ": Block requested " + block);
                        raf.seek(block * MSTCPReceiver.blockSize); // find block
                        raf.read(dataBytes);
                        if (block > mstcpInfo.fileSize / MSTCPReceiver.blockSize) { // if final block then send fin
                            byte[] ackPkt = generateTCPPacket(nextSeqNum, dataBytes, false, true);
                            for (int i=0; i<20; i++) // send 20 in case any get lost
                                outSocket.send(new DatagramPacket(ackPkt, ackPkt.length, dstAddress, dstPort));
                            transferComplete = true; // we done here
                        } else {
                        	logger.info(recvPort + ": Sending Block " + block + " to " + dstAddress + " on port " + dstPort);
                            byte[] ackPkt = generateTCPPacket(nextSeqNum, dataBytes, false, false);
                            outSocket.send(new DatagramPacket(ackPkt, ackPkt.length, dstAddress, dstPort));
                            nextSeqNum++;
                            prevSeqNum = seqNum;
                        }
                    } else { // duplicate packet so we send duplicate ACK and Data
                    	logger.info(recvPort + ": Duplicate request " + seqNum + " received");
                        int block = ByteBuffer.wrap(reqPacket.getData()).getInt();
                        raf.seek(block * MSTCPReceiver.blockSize);
                        raf.read(dataBytes);
                        byte[] ackPkt = generateTCPPacket(nextSeqNum, dataBytes, false, false);
                        outSocket.send(new DatagramPacket(ackPkt, ackPkt.length, dstAddress, dstPort));
                    }
                    
                    transferComplete = reqPacket.isFIN();
                } else { // packet corrupted so send duplicate ACK
                	logger.info(recvPort + ": Corrupted packet received, sending duplicate ACK " + prevSeqNum);
                    if (initialSeqNum == -1)
                        continue;
                    byte[] ackPkt = generateTCPPacket(prevSeqNum, null, false, false);
                    outSocket.send(new DatagramPacket(ackPkt, ackPkt.length, dstAddress, dstPort));
                }
            }
            
            if (raf != null)
                raf.close();
        
        } catch (Exception e) {
            logger.warning(recvPort + ": Exception encountered.");
            e.printStackTrace();
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
