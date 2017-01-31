package MSTCP;

import java.io.File;
import java.io.RandomAccessFile;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.util.Vector;

public class MSTCPSender {
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
        System.out.println("MSTCPSender: Starting up MSTCPSender on port " + recvPort);
        
        this.recvPort = recvPort;
        this.path = path;
        this.srcInfo = new SourceInformation("127.0.0.1", recvPort); // TODO: Change to getting public IP not loopback
        this.sources = sources;
        
        
        
        try {
            inSocket = new DatagramSocket(recvPort);
            outSocket = new DatagramSocket();
            
            byte[] reqBytes = new byte[MSTCPReceiver.requestSize];
            DatagramPacket req = new DatagramPacket(reqBytes, reqBytes.length);
            
            RandomAccessFile raf = null;
            
            byte[] dataBytes = new byte[MSTCPReceiver.blockSize];
            
            System.out.println("MSTCPSender: Waiting for SYN");
            while(!transferComplete) {
                for (int i=0; i < reqBytes.length; i++)
                    reqBytes[i] = 0; // zero out, stop leftovers from previous iteration
                for (int i=0; i < dataBytes.length; i++)
                    dataBytes[i] = 0; // zero out, stop leftovers from previous iteration
                
                
                inSocket.receive(req);
                TCPPacket reqPacket = new TCPPacket(reqBytes);
                
                if (reqPacket.verifyChecksum()) {
                    int seqNum = reqPacket.getSeqNum();
                    if (initialSeqNum == -1) { // expecting a SYN
                        if (reqPacket.isSYN()) {
                            System.out.println("MSTCPSender: SYN Received, initial sequence number " + reqPacket.getSeqNum());
                            initialSeqNum = seqNum;
                            nextSeqNum = initialSeqNum;
                            
                            dstAddress = req.getAddress(); // when moving to lower level we'll need to get this from IP
                            dstPort = reqPacket.getSrcPort();
                            
                            mstcpInfo = new MSTCPInformation(reqPacket.getData());
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
                    } else if (seqNum == nextSeqNum) { // packet received in order, send block
                        System.out.println("MSTCPSender: Request " + seqNum + " received in order");
                        int block = ByteBuffer.wrap(reqPacket.getData()).getInt();
                        System.out.println("MSTCPSender: Block requested " + block);
                        raf.seek(block * MSTCPReceiver.blockSize); // find block
                        raf.read(dataBytes);
                        if (block >= (mstcpInfo.fileSize / MSTCPReceiver.blockSize) + 1) { // if final block then send fin
                            byte[] ackPkt = generateTCPPacket(nextSeqNum, dataBytes, false, true);
                            for (int i=0; i<20; i++) // send 20 in case any get lost
                                outSocket.send(new DatagramPacket(ackPkt, ackPkt.length, dstAddress, dstPort));
                            transferComplete = true; // we done here
                        } else {
                            System.out.println("MSTCPSender: Sending Block " + block + " to " + dstAddress + " on port " + dstPort);
                            byte[] ackPkt = generateTCPPacket(nextSeqNum, dataBytes, false, false);
                            outSocket.send(new DatagramPacket(ackPkt, ackPkt.length, dstAddress, dstPort));
                            nextSeqNum++;
                            prevSeqNum = seqNum;
                        }
                    } else { // duplicate packet so we send duplicate ACK and Data
                        System.out.println("MSTCPSender: Duplicate request " + seqNum + " received");
                        int block = ByteBuffer.wrap(reqPacket.getData()).getInt();
                        raf.seek(block * MSTCPReceiver.blockSize);
                        raf.read(dataBytes);
                        byte[] ackPkt = generateTCPPacket(nextSeqNum, dataBytes, false, false);
                        outSocket.send(new DatagramPacket(ackPkt, ackPkt.length, dstAddress, dstPort));
                    }
                    
                    transferComplete = reqPacket.isFIN();
                } else { // packet corrupted so send duplicate ACK
                    System.out.println("MSTCPSender: Corrupted packet received, sending duplicate ACK " + prevSeqNum);
                    if (initialSeqNum == -1)
                        continue;
                    byte[] ackPkt = generateTCPPacket(prevSeqNum, null, false, false);
                    outSocket.send(new DatagramPacket(ackPkt, ackPkt.length, dstAddress, dstPort));
                }
            }
            
            if (raf != null)
                raf.close();
        
        } catch (Exception e) {
            System.err.println("MSTCPSender: Exception encountered.");
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
