package MSTCP.reno;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.util.Random;
import java.util.Timer;
import java.util.TimerTask;
import java.util.Vector;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.logging.FileHandler;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;


public class MSTCPReceiverConnection extends Thread {
	Logger logger;
	
    // artificially introducing latency
    Random rand = new Random();
    int latency = 1500;
    boolean delay = true;
	
    static final int timeoutVal = Integer.MAX_VALUE; // ms
    static final int mss = MSTCPReceiver.pktSize;        // TODO: verify what this should be    
    
    int cwnd = 1;                           // size of the congestion window in packets
    int cwnd_bytes = MSTCPReceiver.pktSize; // size of the congestion window in bytes
    double rtt_avg;                         // average RTT (measured per RTT, not per ACK)
    int time_recv;                     // used for caclculating RTT
    int rtt_seqNum = -1;                    // seqNum of the packet being used to measure RTT
    int rtts_measured;                      // used for calculating iterative mean
    
    final int connectionID; // identifies this connection between the receiver and a source
    
    final DatagramSocket inSocket, outSocket;  // sockets to receive data and send ACKs
    final int recvPort, dstPort;               // ports to receive data and send ACKs
    final InetAddress dstAddress;              // address of data source
    final int initialSeqNum;                   // random first sequence number
    int base;                            // sequence number of previous, in-order, packet received
    int nextSeqNum;                      // next expected sequence number
    int prevSeqNum = -1;                 
    Semaphore sem_seqNum;                // guard for base and nextSeqSum
    boolean sent_fin = false;            // no more requests coming
    boolean got_fin = false;
    
    Vector<byte[]> sentRequests;         // list of sent requests
    Timer synTimer;                      // for timeouts
    Timer dataTimer;
    LinkedBlockingQueue<TCPPacket> receivedData; // data received in response to a request
    
    final MSTCPReceiver receiver;             // receiver that coordinates connections
    final boolean ms_join;
    
    int synAttempts = 0;
    int reqAttempts = 0;
    
    private void delay() throws InterruptedException {
        Thread.sleep(latency + rand.nextInt(300) - 150);
    }
    
    
    /** Connection Establishment Functionality and Method **/
    
    public class SYNTimeout extends TimerTask {
        public void run() {
            try {
                if (synAttempts < MSTCPReceiver.synLimit) {
                    synAttempts++;
                    sendSYN();
                } else { // give up
                	logger.warning(connectionID + ": Too Many failed SYNs. Giving Up.");
                }
            } catch (Exception e) {
            	logger.warning(connectionID + ": SYNTimeout: Exception while trying to send SYN.");
            	logger.log(Level.SEVERE, e.getMessage(), e);
            	return;
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
        logger.info(connectionID + ": Sending SYN to " + dstAddress.toString() + ", port " + dstPort + ", attempt " + synAttempts);
        
        sem_seqNum.acquire();
        nextSeqNum = initialSeqNum;
        base = initialSeqNum;
        sem_seqNum.release();
        
        TCPPacket synPacket = new TCPPacket(recvPort, dstPort, nextSeqNum, cwnd);
        synPacket.setSYN();
        
        MSTCPInformation msInfo = receiver.mstcpInformation;
        synPacket.setData(msInfo.bytes());
        synPacket.setTime_req();
        
        byte[] synBytes = synPacket.bytes();
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
                if (delay) delay();                
                time_recv = (int) (System.currentTimeMillis() % Integer.MAX_VALUE);

                TCPPacket tcpPacket = new TCPPacket(inData);
                
                if (tcpPacket.verifyChecksum() && tcpPacket.isSYN() && tcpPacket.isACK() && tcpPacket.getACK() == initialSeqNum) {
                    int time_ack = time_recv - tcpPacket.getTime_ack();
                    time_ack = time_ack < 0 ? (Integer.MAX_VALUE - tcpPacket.getTime_ack()) + time_recv : time_ack;
                    long rtt = time_ack + tcpPacket.getTime_req();
                    logger.info(connectionID + ": Received SYN + ACK. RTT: " + rtt);
                    rtt_avg = rtt; 
                    rtts_measured++;
                    if (ms_join)
                        receiver.computeAlpha();
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
        	logger.warning(connectionID + ": Exception Received while Establishing Connection");
        	logger.log(Level.SEVERE, e.getMessage(), e);
            return false;
        }
    }
    
    /** Functionality Shared between InThread and OutThread **/
    
    private void goBackN() throws InterruptedException {
        logger.info(connectionID + ": GoBackN Triggered");
        sem_seqNum.acquire();
        nextSeqNum = base; // do the go-back-n
        sem_seqNum.release();
        
        sent_fin = false;
        
        receiver.computeAlpha();
        rtt_seqNum = -1;
        receiver.sem_cwnd.acquire(); // update congestion window
        int dec = cwnd_bytes / 2;
        receiver.cwnd_bytes_total -= dec;
        cwnd_bytes -= dec;
        cwnd = cwnd_bytes / MSTCPReceiver.pktSize;
        if (cwnd < 1)
            cwnd = 1;
        receiver.computeAlpha();
        receiver.sem_cwnd.release();
    }
    
    public class dataTimeout extends TimerTask {
        public void run() {
            try {
                goBackN();
            } catch (Exception e) {
            	logger.warning(connectionID + ": Timeout: Exception while trying to send SYN");
            	logger.log(Level.SEVERE, e.getMessage(), e);
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
                
                for (;;) {
                    logger.info(connectionID + " Waiting for next ACK.");
                    inSocket.receive(data);
                    if (delay) delay();
                    time_recv = (int) (System.currentTimeMillis() % Integer.MAX_VALUE);
                    TCPPacket tcpPacket = new TCPPacket(inData);
                    
                    if (tcpPacket.verifyChecksum()) { // if packet is corrupted there is not much we can do
                        if (tcpPacket.isACK() && tcpPacket.getACK() != -1) {
                            if (base > tcpPacket.getACK()) { // duplicate ACK, (packet corrupted in transit) need to go back
                                rtt_seqNum = -1;
                                sem_seqNum.acquire();
                                setDataTimer(false);
                                nextSeqNum = base;
                                sem_seqNum.release();
                            } else { // normal ACK and Data
                                if (sent_fin && tcpPacket.isFIN()) {
                                    logger.info(connectionID + ": Got FIN + ACK");
                                    got_fin = true;
                                    setDataTimer(false);
                                    return;
                                }
                                
                                // processing ACK
                                if (tcpPacket.getACK() == rtt_seqNum) { // packet used to measure RTT
                                    rtt_seqNum = -1;
                                    rtts_measured++;
                                    int time_ack = time_recv - tcpPacket.getTime_ack();
                                    time_ack = time_ack < 0 ? (Integer.MAX_VALUE - tcpPacket.getTime_ack()) + time_recv : time_ack;
                                    long rtt = time_ack + tcpPacket.getTime_req();
                                    rtt_avg = rtt_avg + ((1.0/(rtts_measured)) * ((rtt) - rtt_avg));
                                    logger.info(connectionID + ": Avg RTT of connection " + connectionID + ": " + rtt_avg);
                                    receiver.computeAlpha();
                                }
                                sem_seqNum.acquire();
                                base = tcpPacket.getACK() + 1; // update base of window
                                if (base == nextSeqNum) setDataTimer(false); // no outstanding requests so stop timer
                                else setDataTimer(true);                     // otherwise start waiting for the next response
                                sem_seqNum.release();
                                
                                receiver.sem_cwnd.acquire();
                                int bytes_acked = tcpPacket.getData().length;
                                double global = (receiver.alpha * bytes_acked * mss) / receiver.cwnd_bytes_total;
                                double local = ((double) (bytes_acked * mss)) / cwnd_bytes;
                                cwnd_bytes += Math.min(global, local);
                                cwnd = cwnd_bytes /= MSTCPReceiver.pktSize;
                                if (cwnd < 1)
                                    cwnd = 1;
                                receiver.sem_cwnd.release();
                                

                                logger.info(connectionID + ": InThread: Received block from " + dstAddress + ", port " + dstPort);
                                // pass data to receiver
                                receivedData.put(tcpPacket);
                            }
                        }
                    } else{
                        logger.info(connectionID + ": InThread: Data was corrupted");
                    }
                }
            } catch (Exception e) {
            	logger.warning(connectionID + ": InThread: Exception Encountered while Receiving Data.");
            	logger.log(Level.SEVERE, e.getMessage(), e);
            	return;
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
        tcpPacket.setTime_req();
        return tcpPacket.bytes();
    }
    
    
    public class OutThread extends Thread {
        
        public void run() {
            try {
                byte[] request = new byte[MSTCPReceiver.requestSize];
                for (;;) {
                    if (nextSeqNum < base + cwnd) { // if the window is not yet full then send more packets
                        sem_seqNum.acquire();
                        
                        if (base == nextSeqNum) // if first packet in window then start timer
                            setDataTimer(true);
                        
                        
                        if (nextSeqNum - initialSeqNum < sentRequests.size()) { // request has been constructed before (there has been a go-back-n)
                            request = sentRequests.get(nextSeqNum - initialSeqNum);
                        } else {
                            if (sent_fin && !got_fin) { // FIN has been sent, just waiting for timeout or transfer to complete
                                sem_seqNum.release();
                                sleep(2000);
                                continue;
                            } else if (sent_fin && got_fin) {
                                logger.info(connectionID + ": ACKing FIN and closing connection.");
                                request = generateTCPPacket(nextSeqNum, null, true, false);
                                outSocket.send(new DatagramPacket(request, request.length, dstAddress, dstPort));
                                return;
                            }
                            
                            int blockToRequest = receiver.blockToRequest(connectionID);
                            sent_fin = (blockToRequest == -1);
                            
                            ByteBuffer bb = ByteBuffer.allocate(4);
                            bb.putInt(blockToRequest);
                            logger.info(connectionID + " Requesting Block " + blockToRequest);
                            request = generateTCPPacket(nextSeqNum, bb.array(), (nextSeqNum == initialSeqNum), sent_fin); // If first packet then set ACK. If final request then set FIN.
                            sentRequests.add(request);
                        }
                        logger.info(connectionID + ": Sending Request to " + dstAddress + ", port " + dstPort);
                        if (rtt_seqNum == -1) {
                            rtt_seqNum = nextSeqNum;
                        }
                        outSocket.send(new DatagramPacket(request, request.length, dstAddress, dstPort));
                        nextSeqNum++;
                        sem_seqNum.release();
                    } else {
                        sleep(5);
                    }
                                       
                }
                
            } catch (Exception e) {
                logger.warning(connectionID + ": InThread: Exception Encountered while Receiving Data.");
                logger.log(Level.SEVERE, e.getMessage(), e);
                return;
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
    
    
    public MSTCPReceiverConnection(String addr, int recvPort, int dstPort, MSTCPReceiver receiver, int connectionID, boolean ms_join) throws SocketException, UnknownHostException {   	    	
    	this.dstAddress = InetAddress.getByName(addr);
        this.recvPort = recvPort;
        this.dstPort = dstPort;
        this.receiver = receiver;
        this.connectionID = connectionID;
        this.ms_join = ms_join;
        inSocket = new DatagramSocket(recvPort); // receive packets on port recvPort
        outSocket = new DatagramSocket();        // send REQs and ACKs on any available port    
        
        // Choose a random initial sequence number
        Random rand = new Random();
        initialSeqNum = rand.nextInt(100);                

        
        sem_seqNum = new Semaphore(1);
        sentRequests = new Vector<byte[]>(cwnd);
        receivedData = new LinkedBlockingQueue<TCPPacket>();
        
        logger = Logger.getLogger( MSTCPSender.class.getName() + this.connectionID );
        try {
            FileHandler handler = new FileHandler("./logs/MSTCPReceiverConnection_" + this.connectionID +".log", 1048576, 1, false);
            handler.setFormatter(new SimpleFormatter());
            logger.setUseParentHandlers(false);
            logger.addHandler(handler);
            logger.setLevel(Level.ALL);
        } catch (SecurityException | IOException e1) {
        	System.err.println("MSTCPSender: " + connectionID + ": Unable to Connect to Logger");
            e1.printStackTrace();
            return;
        }
        logger.info("*** NEW RUN ***");
        
        if (!ms_join)
            this.run(); // if first connection then we must wait for connection to be established before continuing
        else
            this.start(); // for subsequent ones we don't want to have to wait
        
        logger.info(connectionID + ": Started up.");
    }
}
