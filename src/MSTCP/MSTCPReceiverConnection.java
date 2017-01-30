package MSTCP;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Random;
import java.util.Timer;
import java.util.TimerTask;
import java.util.Vector;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;

import MSTCP.TCPPacket;


public class MSTCPReceiverConnection extends Thread {
    static int timeoutVal = 300; // ms
    int winSize = 1;
    
    int id;
    
    DatagramSocket inSocket, outSocket;  // sockets to receive data and send ACKs
    int recvPort, dstPort;               // ports to receive data and semd ACKs
    InetAddress dstAddress;              // address of data source
    int initialSeqNum;                   // random first sequence number
    int base;                            // sequence number of previous, in-order, packet received
    int nextSeqNum;                      // next expected sequence number
    int prevSeqNum = -1;                 
    Semaphore s;                         // guard for base and nextSeqSum
    boolean complete = false;            // no more requests coming
    
    Vector<byte[]> sentRequests;         // list of sent requests
    Timer synTimer;                      // for timeouts
    Timer dataTimer;
    LinkedBlockingQueue<byte[]> receivedData; // data received in response to a request
    
    MSTCPReceiver receiver;             // receiver that coordinates connections
    boolean MS_JOIN;                    // first connection or joining existing
    
    /** Connection Establishment Functionality and Method **/
    int synAttempts = 0;
    
    public class SYNTimeout extends TimerTask {
        public void run() {
            try {
                if (synAttempts < MSTCPReceiver.synLimit) {
                    synAttempts++;
                    sendSYN();
                } else { // give up
                    System.err.println("MSTCPReceiverConnection: Too Many failed SYNs. Giving Up.");
                }
            } catch (Exception e) {
                System.err.println("MSTCPReceiverConnection: Timeout: Exception while trying to send SYN. Attempting to Carry On");
                e.printStackTrace();
            }
        }
    }
    
    public void setSYNTimer(boolean newTimer) {
        if (synTimer != null) synTimer.cancel(); // stops the current timer
        if (newTimer) {
            synTimer = new Timer(); // start a new one if necessary
            synTimer.schedule(new SYNTimeout(), timeoutVal);
        }
    }
    
    
   
    private void sendSYN() throws IOException, InterruptedException {
        // Choose a random initial sequence number
        Random rand = new Random();
        
        s.acquire();
        initialSeqNum = rand.nextInt(100);
        nextSeqNum = initialSeqNum;
        base = initialSeqNum;
        s.release();
        
        // TODO: set MS_JOIN if necessary
        
        TCPPacket synPacket = new TCPPacket(recvPort, dstPort, nextSeqNum, winSize);
        synPacket.setSYN();
        byte[] synBytes = synPacket.bytes();
        outSocket.send(new DatagramPacket(synBytes, synBytes.length, dstAddress, dstPort));
        setSYNTimer(true); // timeout if there's no reply
    }
    
    private boolean establishConnection() {
        try {
            boolean connectionEstablished = false;
            
            byte[] inData = new byte[MSTCPReceiver.headerSize];
            DatagramPacket inPkt = new DatagramPacket(inData, inData.length);
            
            sendSYN();
          
            while (!connectionEstablished) {
                inSocket.receive(inPkt); // receive a packet

                TCPPacket tcpPacket = new TCPPacket(inData);
                
                if (tcpPacket.verifyChecksum() && tcpPacket.isSYN() && tcpPacket.isACK() && tcpPacket.getACK() == initialSeqNum) {
                    setSYNTimer(false);
                    connectionEstablished = true;
                    // (ACK is included with first request)
                }
            }
            return true;
        } catch (Exception e) {
            System.err.println("MSTCPConnection: Exception Received while Establishing Connection");
            e.printStackTrace();
            return false;
        }
    }
    
    /** Functionality Shared between InThread and OutThread **/
    
    private void goBackN() throws InterruptedException {
        s.acquire();
        nextSeqNum = base; // do the go-back-n
        s.release();
    }
    
    public class dataTimeout extends TimerTask {
        public void run() {
            System.out.println("TCPReceiver: Timeout: Resending SYN");
            try {
                goBackN();
            } catch (Exception e) {
                System.err.println("TCPReceiver: Timeout: Exception while trying to send SYN");
                e.printStackTrace();
            }
        }
    }
    
    
    public void setDataTimer(boolean newTimer) {
        if (synTimer != null) synTimer.cancel(); // stops the current timer
        if (newTimer) {
            synTimer = new Timer(); // start a new one if necessary
            synTimer.schedule(new SYNTimeout(), timeoutVal);
        }
    }
    
    
    
    /** InThread Functionality and Class **/
            
    public class InThread extends Thread {
        
        public void run() {
            try {
                byte[] inData = new byte[MSTCPReceiver.pktSize];
                DatagramPacket data = new DatagramPacket(inData, inData.length);
                
                while (!receiver.transferComplete) {
                    inSocket.receive(data);
                    int tcpDataOffset = (inData[12] >> 4); // in 32 bit words
                    int tcpHeaderLength = tcpDataOffset * 4; // in bytes
                    TCPPacket tcpPacket = new TCPPacket(Arrays.copyOfRange(inData, 0, tcpHeaderLength));
                    
                    if (tcpPacket.verifyChecksum()) { // if packet is corrupted there is not much we can do
                        if (tcpPacket.isACK() && tcpPacket.getACK() != -1) {
                            if (base > tcpPacket.getACK()) { // duplicate ACK, need to go back
                                s.acquire();
                                setDataTimer(false);
                                nextSeqNum = base;
                                s.release();
                            } else { // normal ACK and DATA
                                // processing ACK
                                s.acquire();
                                base = tcpPacket.getACK() + 1; // update base of window
                                if (base == nextSeqNum) setDataTimer(false); // no outstanding requests so stop timer
                                else setDataTimer(true);                     // otherwise start waiting for the next response
                                s.release();
                                
                                // pass data to receiver
                                receivedData.put(Arrays.copyOfRange(inData, tcpHeaderLength, inData.length));
                            }
                        }
                    }
                }
                
                setDataTimer(false);
            } catch (Exception e) {
                System.err.println("MSTCPConnection: InThread: Exception Encountered while Receiving Data. Attempting to Carry On");
                e.printStackTrace();
            } finally {
                inSocket.close();
            }
        }
    }
    
    
    /** OutThread Functionality and Class **/
    
    
    // attaches a TCP header to the data bytes
    public byte[] generateTCPPacket(int seqNum, byte[] dataBytes, boolean ack) {
        TCPPacket tcpPacket = new TCPPacket(this.recvPort, this.dstPort, seqNum, this.winSize);
        if (ack) {
            tcpPacket.setACK(initialSeqNum);
        }
        
        byte[] tcpBytes = tcpPacket.bytes();
        
        ByteBuffer packetBuffer = ByteBuffer.allocate(tcpBytes.length + dataBytes.length);
        packetBuffer.put(tcpBytes);
        packetBuffer.put(dataBytes);
        return packetBuffer.array();
    }
    
    
    public class OutThread extends Thread {
        
        public void run() {
            try {
                byte[] request = new byte[MSTCPReceiver.requestSize];
                while (!receiver.transferComplete) {
                    if (nextSeqNum < base + winSize) { // if the window is not yet full then send more packets
                        s.acquire();
                        
                        if (base == nextSeqNum) // if first packet in window then start timer
                            setDataTimer(true);
                        
                        
                        if (nextSeqNum - initialSeqNum < sentRequests.size()) { // request has been constructed before (there has been a go-back-n)
                            request = sentRequests.get(nextSeqNum - initialSeqNum);
                        } else {
                            int blocktoRequest = receiver.blockToRequest(id);
                            ByteBuffer bb = ByteBuffer.allocate(4);
                            bb.putInt(blocktoRequest);
                            request = generateTCPPacket(nextSeqNum, bb.array(), (nextSeqNum == initialSeqNum)); // if first packet then set ACK to complete connection set up
                            sentRequests.add(request);
                        }
                        outSocket.send(new DatagramPacket(request, request.length, dstAddress, dstPort));
                        nextSeqNum++;
                        s.release();
                    }
                    sleep(5);
                    
                }
            } catch (Exception e) {
                System.err.println("MSTCPConnection: InThread: Exception Encountered while Receiving Data. Attempting to Carry On");
                e.printStackTrace();
            } finally {
                outSocket.close();
            }
        }
    }
    
    
    public MSTCPReceiverConnection(String addr, int recvPort, int dstPort, boolean MS_JOIN, MSTCPReceiver receiver, int id) {
        System.out.println("MSTCPConnection: Starting Up");
        
        try {
            this.dstAddress = InetAddress.getByName(addr);
            this.recvPort = recvPort;
            this.dstPort = dstPort;
            this.receiver = receiver;
            this.MS_JOIN = MS_JOIN;
            this.id = id;
            
            inSocket = new DatagramSocket(recvPort); // receive packets on port recvPort
            outSocket = new DatagramSocket();        // send REQs and ACKs on any available port
            
            s = new Semaphore(1);
            sentRequests = new Vector<byte[]>(winSize);
            receivedData = new LinkedBlockingQueue<byte[]>();
            
            InThread inThread = new InThread();
            OutThread outThread = new OutThread();
            
            if (establishConnection()) {
                inThread.start();
                outThread.start();
            }
            
        } catch (Exception e) {
            System.err.println("MSTCPConnection: Exception Encountered: Attempting to Carry On");
            e.printStackTrace();
        }
    }
}
