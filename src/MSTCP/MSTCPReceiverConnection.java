package MSTCP;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.util.Random;
import java.util.Timer;
import java.util.TimerTask;
import java.util.Vector;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;


public class MSTCPReceiverConnection extends Thread {
    static int timeoutVal = 300; // ms
    
    Semaphore sem_cwnd;               // guard for cwnd
    int cwnd = 1;                     // size of the congestion window
    int mss = MSTCPReceiver.pktSize;  // TODO: verify what this should be
    float rtt_avg;                    // average RTT (measured per RTT, not per ACK)
    long time_sent;                   // used for caclculating RTT
    long time_received;               // used for caclculating RTT
    int rtt_seqNum = -1;              // seqNum of the packet being used to measure RTT
    int rtts_measured;                // used for calculating iterative mean
    
    int connectionID; // identifies this connection between the receiver and a source
    
    DatagramSocket inSocket, outSocket;  // sockets to receive data and send ACKs
    int recvPort, dstPort;               // ports to receive data and semd ACKs
    InetAddress dstAddress;              // address of data source
    int initialSeqNum;                   // random first sequence number
    int base;                            // sequence number of previous, in-order, packet received
    int nextSeqNum;                      // next expected sequence number
    int prevSeqNum = -1;                 
    Semaphore sem_seqNum;                // guard for base and nextSeqSum
    boolean complete = false;            // no more requests coming
    
    Vector<byte[]> sentRequests;         // list of sent requests
    Timer synTimer;                      // for timeouts
    Timer dataTimer;
    LinkedBlockingQueue<TCPPacket> receivedData; // data received in response to a request
    
    MSTCPReceiver receiver;             // receiver that coordinates connections
    boolean ms_join;
    
    int synAttempts = 0;
    int reqAttempts = 0;
    
    
    
    /** Connection Establishment Functionality and Method **/
    
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
                System.err.println("MSTCPReceiverConnection: SYNTimeout: Exception while trying to send SYN. Attempting to Carry On");
                e.printStackTrace();
            }
        }
    }
    
    public void setSYNTimer(boolean newTimer) {
        if (synTimer != null)
            synTimer.cancel(); // stops the current timer
        if (newTimer) {
            synTimer = new Timer(); // start a new one if necessary
            synTimer.schedule(new SYNTimeout(), timeoutVal);
        }
    }
    
    
   
    private void sendSYN() throws IOException, InterruptedException {
        System.out.println("MSTCPReceiverConnection: Sending SYN to " + dstAddress.toString() + ", port " + dstPort + ", attempt " + synAttempts);
        // Choose a random initial sequence number
        Random rand = new Random();
        
        sem_seqNum.acquire();
        initialSeqNum = rand.nextInt(100);
        nextSeqNum = initialSeqNum;
        base = initialSeqNum;
        sem_seqNum.release();
        
        TCPPacket synPacket = new TCPPacket(recvPort, dstPort, nextSeqNum, cwnd);
        synPacket.setSYN();
        
        MSTCPInformation msInfo = receiver.mstcpInformation;
        synPacket.setData(msInfo.bytes());
        
        byte[] synBytes = synPacket.bytes();
        time_sent = System.currentTimeMillis();
        outSocket.send(new DatagramPacket(synBytes, synBytes.length, dstAddress, dstPort));
        setSYNTimer(true); // timeout if there's no reply
    }
    
    private boolean establishConnection() {
        try {
            boolean connectionEstablished = false;
            
            byte[] inData = new byte[1000];
            DatagramPacket inPkt = new DatagramPacket(inData, inData.length);
            
            sendSYN();
            while (!connectionEstablished) {
                inSocket.receive(inPkt); // receive a packet
                time_received = System.currentTimeMillis();

                TCPPacket tcpPacket = new TCPPacket(inData);
                
                if (tcpPacket.verifyChecksum() && tcpPacket.isSYN() && tcpPacket.isACK() && tcpPacket.getACK() == initialSeqNum) {
                    System.out.println("MSTCPReceiverConnection: Received SYN + ACK");
                    rtt_avg = time_received - time_sent;
                    rtts_measured++;
                    setSYNTimer(false);
                    connectionEstablished = true;
                    // (ACK is included with first request)
                    
                    if (!ms_join) { // if first connection then need to set MSTCPInformation
                        receiver.mstcpInformation = new MSTCPInformation(tcpPacket.getData());
                    }
                }
            }
            return true;
        } catch (Exception e) {
            System.err.println("MSTCPReceiverConnection: Exception Received while Establishing Connection");
            e.printStackTrace();
            return false;
        }
    }
    
    /** Functionality Shared between InThread and OutThread **/
    
    private void goBackN() throws InterruptedException {
        sem_seqNum.acquire();
        nextSeqNum = base; // do the go-back-n
        sem_seqNum.release();
        
        rtt_seqNum = -1;
        sem_cwnd.acquire(); // update congestion window
        if (cwnd > 1)
            cwnd /= 1;
        // TODO: recalculate alpha
        sem_cwnd.release();
    }
    
    public class dataTimeout extends TimerTask {
        public void run() {
            try {
                goBackN();
            } catch (Exception e) {
                System.err.println("MSTCPReceiverConnection: Timeout: Exception while trying to send SYN");
                e.printStackTrace();
            }
        }
    }
    
    
    public void setDataTimer(boolean newTimer) {
        if (synTimer != null) synTimer.cancel(); // stops the current timer
        if (newTimer) {
            synTimer = new Timer(); // start a new one if necessary
            synTimer.schedule(new dataTimeout(), timeoutVal);
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
                    time_received = System.currentTimeMillis();
                    TCPPacket tcpPacket = new TCPPacket(inData);
                    
                    if (tcpPacket.verifyChecksum()) { // if packet is corrupted there is not much we can do
                        if (tcpPacket.isACK() && tcpPacket.getACK() != -1) {
                            if (base > tcpPacket.getACK()) { // duplicate ACK, need to go back
                                rtt_seqNum = -1;
                                sem_seqNum.acquire();
                                setDataTimer(false);
                                nextSeqNum = base;
                                sem_seqNum.release();
                            } else { // normal ACK and Data
                                // processing ACK
                                if (tcpPacket.getACK() == rtt_seqNum) {
                                    rtt_seqNum = -1;
                                    rtts_measured++;
                                    rtt_avg = rtt_avg + ((1/((float)rtts_measured)) * ((time_received-time_sent) - rtt_avg));
                                    System.out.println("Avg RTT: " + rtt_avg);
                                }
                                sem_seqNum.acquire();
                                base = tcpPacket.getACK() + 1; // update base of window
                                if (base == nextSeqNum) setDataTimer(false); // no outstanding requests so stop timer
                                else setDataTimer(true);                     // otherwise start waiting for the next response
                                sem_seqNum.release();
                                
                                receiver.transferComplete = tcpPacket.isFIN();
                                if (receiver.transferComplete)
                                    System.out.println("MSTCPReceiverConnection: InThread: Received FIN. We done here.");

                                System.out.println("MSTCPReceiverConnection: InThread: Received block from " + dstAddress + ", port " + dstPort);
                                // pass data to receiver
                                receivedData.put(tcpPacket);
                            }
                        }
                    } else{
                        System.out.println("MSTCPReceiverConnection: InThread: Data was corrupted");
                    }
                }
                
                setDataTimer(false);
            } catch (Exception e) {
                System.err.println("MSTCPReceiverConnection: InThread: Exception Encountered while Receiving Data. Attempting to Carry On");
                e.printStackTrace();
            } finally {
                inSocket.close();
            }
        }
    }
    
    
    
    /** OutThread Functionality and Class **/
    
    // attaches a TCP header to the data bytes
    public byte[] generateTCPPacket(int seqNum, byte[] dataBytes, boolean ack, boolean fin) {
        TCPPacket tcpPacket = new TCPPacket(this.recvPort, this.dstPort, seqNum, this.cwnd, dataBytes);
        if (ack)
            tcpPacket.setACK(initialSeqNum);
        if (fin)
            tcpPacket.setFIN();
        return tcpPacket.bytes();
    }
    
    
    public class OutThread extends Thread {
        
        public void run() {
            try {
                byte[] request = new byte[MSTCPReceiver.requestSize];
                while (!receiver.transferComplete) {
                    if (nextSeqNum < base + cwnd) { // if the window is not yet full then send more packets
                        sem_seqNum.acquire();
                        
                        if (base == nextSeqNum) // if first packet in window then start timer
                            setDataTimer(true);
                        
                        
                        if (nextSeqNum - initialSeqNum < sentRequests.size()) { // request has been constructed before (there has been a go-back-n)
                            request = sentRequests.get(nextSeqNum - initialSeqNum);
                        } else {
                            int blockToRequest = receiver.blockToRequest(connectionID);
                            if (blockToRequest == -1) // either uninitialised or all requests sent
                                continue;
                            ByteBuffer bb = ByteBuffer.allocate(4);
                            bb.putInt(blockToRequest);
                            request = generateTCPPacket(nextSeqNum, bb.array(), (nextSeqNum == initialSeqNum), false); // if first packet then set ACK to complete connection set up
                            sentRequests.add(request);
                        }
                        System.out.println("MSTCPReceiverConnection: Sending Request to " + dstAddress + ", port " + dstPort);
                        if (rtt_seqNum == -1) {
                            rtt_seqNum = nextSeqNum;
                            time_sent = System.currentTimeMillis();
                        }
                        outSocket.send(new DatagramPacket(request, request.length, dstAddress, dstPort));
                        nextSeqNum++;
                        sem_seqNum.release();
                    }
                    
                    sleep(5);                    
                }
                
                request = generateTCPPacket(nextSeqNum, null, false, true);
                for (int i=0; i<20; i++)
                    outSocket.send(new DatagramPacket(request, request.length, dstAddress, dstPort));
                
            } catch (Exception e) {
                System.err.println("MSTCPReceiverConnection: InThread: Exception Encountered while Receiving Data. Attempting to Carry On");
                e.printStackTrace();
            } finally {
                outSocket.close();
            }
        }
    }
    
    
    public void run() {
        InThread inThread = new InThread();
        OutThread outThread = new OutThread();
        
        if (establishConnection()) {
            inThread.start();
            outThread.start();
        } 
    }
    
    
    public MSTCPReceiverConnection(String addr, int recvPort, int dstPort, MSTCPReceiver receiver, int connectionID, boolean ms_join) {
        System.out.println();
        try {
            this.dstAddress = InetAddress.getByName(addr);
            this.recvPort = recvPort;
            this.dstPort = dstPort;
            this.receiver = receiver;
            this.connectionID = connectionID;
            this.ms_join = ms_join;
                    
            
            inSocket = new DatagramSocket(recvPort); // receive packets on port recvPort
            outSocket = new DatagramSocket();        // send REQs and ACKs on any available port
            
            sem_seqNum = new Semaphore(1);
            sem_cwnd = new Semaphore(1);
            sentRequests = new Vector<byte[]>(cwnd);
            receivedData = new LinkedBlockingQueue<TCPPacket>();
            
            if (!ms_join)
                this.run(); // if first connection then we must wait for connection to be established before continuing
            else
                this.start(); // for subsequent ones we don't want to have to wait

        } catch (Exception e) {
            System.err.println("MSTCPReceiverConnection: Exception Encountered: Attempting to Carry On");
            e.printStackTrace();
        }
    }
}
