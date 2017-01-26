package TCPoverUDP;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Random;
import java.util.Timer;
import java.util.TimerTask;

/**
 * 
 * @author Nathanael Davison = nd359
 * 
 *      Simple implementation of Go-Back-N over UDP
 *          - See comments on TCPSender for more information
 *
 */
public class TCPReceiver {
    static int pktSize = 1000; // checksum: 8, sequNum: 4, data <= 988, so 1000 Bytes total
    DatagramSocket inSocket, outSocket;
    int initialSeqNum;
    int prevSeqNum = -1;                   // sequence number of previous, in-order, packet received
    int nextSeqNum;                   // next expected sequence number    
    Timer timer;                      // for timeouts while setting up connection
    boolean sentSyn = false;
    int recvPort, dstPort;
    InetAddress dstAddress;

    
    public class Timeout extends TimerTask {
        public void run() {
            System.out.println("TCPReceiver: Timeout: Resending SYN");
            try {
                sendSYN();
            } catch (IOException e) {
                System.err.println("TCPReceiver: Timeout: Exception while trying to send SYN");
                e.printStackTrace();
            }
        }
    }
    
    
    public void setTimer(boolean newTimer) {
        if (timer != null) timer.cancel(); // stops the current timer
        if (newTimer) {
            timer = new Timer(); // start a new one if necessary
            timer.schedule(new Timeout(), TCPSender.timeoutVal);
        }
    }
    
    private void sendSYN() throws IOException {
        Random rand = new Random();
        initialSeqNum = rand.nextInt(100);
        nextSeqNum = initialSeqNum;
        
        System.out.println("TCPReceiver: Sending SYN, initial sequence number " + initialSeqNum);
        
        TCPPacket synPacket = new TCPPacket(recvPort, dstPort, nextSeqNum, TCPSender.winSize);
        synPacket.setSYN();
        byte[] synBytes = synPacket.bytes();
        sentSyn = true;
        outSocket.send(new DatagramPacket(synBytes, synBytes.length, dstAddress, dstPort));
        setTimer(true);
    }
    
    
    
    public TCPReceiver(int recvPort, int dstPort, String path) {
        System.out.println("TCPReceiver: Starting Up TCPReceiver");
        this.recvPort = recvPort;
        this.dstPort = dstPort;

        boolean transferComplete = false;      // if we have received last packet and send terminate ACK


        try {
        
            // create the sockets
            inSocket = new DatagramSocket(recvPort); // receive packets on port recvPort
            outSocket = new DatagramSocket();        // send ACKs on any available port
            System.out.println("TCPReveiver: Started Up");
            
            try {
                dstAddress = InetAddress.getByName("127.0.0.1");
                byte[] inData = new byte[pktSize];
                DatagramPacket inPkt = new DatagramPacket(inData, inData.length);
                FileOutputStream fos = null;
                path = ((path.substring(path.length()-1)).equals("/")) ? path : path + "/"; // properly format path
                File filePath = new File(path); 
                if (!filePath.exists()) filePath.mkdir(); // make directory if necessary
                
                
                
                
                // send SYN
                sendSYN();
                                
                while(!transferComplete) { // while there are still packets to receive
                    
                    inSocket.receive(inPkt); // receive a packet
                    
                    
                    int tcpDataOffset = (inData[12] >> 4); // in 32 bit words
                    int tcpHeaderLength = tcpDataOffset * 4; // in bytes
                    TCPPacket tcpPacket = new TCPPacket(Arrays.copyOfRange(inData, 0, tcpHeaderLength));
                    
                    System.out.println("TCPReceiver: Received TCP Packet: " + tcpPacket.getSeqNum());
                
                    if (tcpPacket.verifyChecksum()) {
                        
                        int seqNum = tcpPacket.getSeqNum();
                        
                        if (seqNum == nextSeqNum) { // if packet received in order
                            
                            if (tcpPacket.isSYN() && tcpPacket.isACK() && tcpPacket.getACK() == initialSeqNum) {
                                System.out.println("TCPReceiver: SYN + ACK received, sending ACK: " + seqNum);
                                setTimer(false);
                                byte[] ackPkt = generateTCPACKPacket(tcpPacket.getDestPort(), tcpPacket.getSrcPort(), seqNum, tcpPacket.getWindowSize(), false);
                                outSocket.send(new DatagramPacket(ackPkt, ackPkt.length, dstAddress, dstPort));
                            } else if (tcpPacket.isFIN()) { // if final packet (no data) then send teardown ACK
                                System.out.println("TCPReceiver: Sending TCP ACK + FIN: " + seqNum);
                                byte[] ackPkt = generateTCPACKPacket(tcpPacket.getDestPort(), tcpPacket.getSrcPort(), seqNum, tcpPacket.getWindowSize(), true);
                                for (int i=0; i<20; i++) outSocket.send(new DatagramPacket(ackPkt, ackPkt.length, dstAddress, dstPort)); // send 20 in case some are lost in the way
                                transferComplete = true; // we done here
                                continue; // end listener
                            } else { // otherwise send a normal ACK
                                byte[] ackPkt = generateTCPACKPacket(tcpPacket.getDestPort(), tcpPacket.getSrcPort(), seqNum, tcpPacket.getWindowSize(), false);
                                System.out.println("TCPReceiver: Sending TCP ACK: " + seqNum);
                                outSocket.send(new DatagramPacket(ackPkt, ackPkt.length, dstAddress, dstPort));
                            }
                            
                            if (seqNum == initialSeqNum && prevSeqNum == -1) { // if first packet
                                int filenameLength = ByteBuffer.wrap(Arrays.copyOfRange(inData, tcpHeaderLength, tcpHeaderLength + 4)).getInt();
                                String filename = new String(Arrays.copyOfRange(inData, tcpHeaderLength + 4, tcpHeaderLength + 4 + filenameLength)); // decode filename
                                System.out.println("TCPReceiver: Receiving file " + filename);
                                
                                File file = new File(path + "received_" + filename); // create the file
                                if (!file.exists()) file.createNewFile();
                                fos = new FileOutputStream(file);
                                fos.write(inData, tcpHeaderLength + 4 + filenameLength, inPkt.getLength() - (tcpHeaderLength + 4) - filenameLength); // initial data
                            } else { // otherwise just continue to add it to the current file
                                fos.write(inData, tcpHeaderLength, inPkt.getLength() - tcpHeaderLength);
                            }
                            
                            nextSeqNum++; // update nextSeqNum
                            prevSeqNum = seqNum; // update prevSeqNum      
                        } else { // if duplicate packet then send duplicate ACK
                            byte[] ackPkt = generateTCPACKPacket(tcpPacket.getDestPort(), tcpPacket.getSrcPort(), prevSeqNum, tcpPacket.getWindowSize(), false);
                            System.out.println("TCPReceiver: Sending Duplicate TCP ACK (duplicate): " + prevSeqNum);
                            outSocket.send(new DatagramPacket(ackPkt, ackPkt.length, dstAddress, dstPort));
                        }
                    } else { // packet is corrupted so we send duplicate ACK
                        byte[] ackPkt = generateTCPACKPacket(tcpPacket.getDestPort(), tcpPacket.getSrcPort(), prevSeqNum, tcpPacket.getWindowSize(), false);
                        System.out.println("TCPReceiver: Sending Duplicate TCP ACK (corrupted): " + prevSeqNum);
                        outSocket.send(new DatagramPacket(ackPkt, ackPkt.length, dstAddress, dstPort));
                        System.out.println("TCPReceiver: Corrupted packet dropped, sent duplicate ACK " + prevSeqNum);
                    }
                }
                if (fos != null) fos.close();
            } catch (UnknownHostException e) {
                System.err.println("TCPReceiver: Unable to resolve host");
                e.printStackTrace();
                System.exit(-1);
            } catch (FileNotFoundException e) {
                System.err.println("TCPReceiver: Unable to find file " + path);
                e.printStackTrace();
                System.exit(-1);
            } catch (IOException e) {
                System.err.println("TCPReceiver: Exception while communicating");
                e.printStackTrace();
                System.exit(-1);
            } finally {
                outSocket.close();
                inSocket.close();
            }
        } catch (SocketException e) {
            System.err.println("TCPReceiverer: Exception while creating sockets. Attempting to carry on.");
            e.printStackTrace();
        }
        
    }
    
    
    // Generate TCP ACK packet
    public byte[] generateTCPACKPacket(int srcPort, int destPort, int ackNum, int windowSize, boolean fin) {
        TCPPacket tcpPacket = new TCPPacket(srcPort, destPort, ackNum, windowSize);
        tcpPacket.setACK(ackNum);
        if (fin) tcpPacket.setFIN();
        return tcpPacket.bytes();
    }
    
    public static void main(String[] args) {
        new TCPReceiver(14415,14416,"./"); // recvPort, dstPort
    }

}
