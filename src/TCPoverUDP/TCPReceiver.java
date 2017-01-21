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
    
    public TCPReceiver(int recvPort, int dstPort, String path) {
        System.out.println("TCPReceiver: Starting Up TCPReceiver");
        DatagramSocket inSocket, outSocket;
        int prevSeqNum = -1;                // sequence number of previous, in-order, packet received
        int nextSeqNum = 0;                 // next expected sequence number
        boolean transferComplete = false;   // if we have received last packet and send terminate ACK
        
        try {
        
            // create the sockets
            inSocket = new DatagramSocket(recvPort); // receive packets on port recvPort
            outSocket = new DatagramSocket();        // send ACKs on any available port
            System.out.println("TCPReveiver: Started Up");
            
            try {
            
                byte[] inData = new byte[pktSize];
                DatagramPacket inPkt = new DatagramPacket(inData, inData.length);
                InetAddress dstAddress = InetAddress.getByName("127.0.0.1");
                FileOutputStream fos = null;
                path = ((path.substring(path.length()-1)).equals("/")) ? path : path + "/"; // properly format path
                File filePath = new File(path); 
                if (!filePath.exists()) filePath.mkdir(); // make directory if necessary
                
                while(!transferComplete) { // while there are still packets to receive
                    
                    inSocket.receive(inPkt); // receive a packet
                    
                    
                    int tcpDataOffset = (inData[12] >> 4); // in 32 bit words
                    int tcpHeaderLength = tcpDataOffset * 4; // in bytes
                    TCPPacket tcpPacket = new TCPPacket(Arrays.copyOfRange(inData, 0, tcpHeaderLength));
                    
                    System.out.println("TCPReceiver: Received TCP Packet: " + tcpPacket.getSeqNum());
                
                    if (tcpPacket.verifyChecksum()) {
                        
                        int seqNum = tcpPacket.getSeqNum();
                        
                        if (seqNum == nextSeqNum) { // if packet received in order
                            
                            if (tcpPacket.isFIN()) { // if final packet (no data) then send teardown ACK
                                System.out.println("TCPReceiver: Sending TCP ACK + FIN: " + seqNum);
                                byte[] ackPkt = generateTCPPacket(tcpPacket.getDestPort(), tcpPacket.getSrcPort(), seqNum, tcpPacket.getWindowSize());
                                for (int i=0; i<20; i++) outSocket.send(new DatagramPacket(ackPkt, ackPkt.length, dstAddress, dstPort)); // send 20 in case some are lost in the way
                                transferComplete = true; // we done here
                                continue; // end listener
                            } else { // otherwise send a normal ACK
                                byte[] ackPkt = generateTCPPacket(tcpPacket.getDestPort(), tcpPacket.getSrcPort(), seqNum, tcpPacket.getWindowSize());
                                System.out.println("TCPReceiver: Sending TCP ACK: " + seqNum);
                                outSocket.send(new DatagramPacket(ackPkt, ackPkt.length, dstAddress, dstPort));
                            }
                            
                            if (seqNum == 0 && prevSeqNum == -1) { // if first packet
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
                            byte[] ackPkt = generateTCPPacket(tcpPacket.getDestPort(), tcpPacket.getSrcPort(), prevSeqNum, tcpPacket.getWindowSize());
                            System.out.println("TCPReceiver: Sending Duplicate TCP ACK (duplicate): " + prevSeqNum);
                            outSocket.send(new DatagramPacket(ackPkt, ackPkt.length, dstAddress, dstPort));
                        }
                    } else { // packet is corrupted so we send duplicate ACK
                        byte[] ackPkt = generateTCPPacket(tcpPacket.getDestPort(), tcpPacket.getSrcPort(), prevSeqNum, tcpPacket.getWindowSize());
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
    public byte[] generateTCPPacket(int srcPort, int destPort, int ackNum, int windowSize) {
        TCPPacket tcpPacket = new TCPPacket(srcPort, destPort, -1, windowSize);
        tcpPacket.setACK(ackNum);
        return tcpPacket.bytes();
    }
    
    public static void main(String[] args) {
        new TCPReceiver(14415,14416,"./"); // recvPort, dstPort
    }

}