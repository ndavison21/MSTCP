package MSTCP.vegas;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.util.LinkedList;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.logging.Level;
import java.util.logging.Logger;

public class MSTCPRequesterConnection extends Thread {
    final Logger logger;
    
    final int recvPort;
    final InetAddress dstAddr;
    final int dstPort;
    MSTCPRequester requester;
    
    final MSTCPSocket inSocket;
    final MSTCPSocket outSocket;
    
    LinkedList<TCPPacket> sentRequests;
    LinkedBlockingQueue<TCPPacket> receivedData;
    
    Timer timer;
    int synAttempts = 0;
    
    // rtt measurements
    int time_recv    = -1;
    int time_ack     = -1;
    long base_rtt    = -1;
    int pkt_rtt      = -1;
    long sampled_rtt = 0;
    int sampled_num  = 0;
    double rtt       = -1;
    double diff      = -1;
    
    // congestion control
    boolean slowstart = false; // TODO: Implement slow start
    int ssthresh      = Integer.MAX_VALUE;
    int cwnd          = 2;
    double cwnd_true  = 2.0;
    double alpha      = 2;
    double weight     = 0;
    double q          = -1;
    double queue_delay     = 0;
    double backoff_factor  = -1;
    Double equilibrium_rate = 0.0;
    
    // fast retransmit
    LinkedBlockingQueue<TCPPacket> toRetransmit;
    
    
    final Integer initialSeqNum = Utils.rand.nextInt(1000);
    int base       = initialSeqNum;
    int nextSeqNum = initialSeqNum;
    int prevSeqNum = -1;
    int rttSeqNum  = initialSeqNum;
    
    
    /**
     * Timeout stuff
     */
    private void stopTimer() {
        if (timer != null)
            timer.cancel(); // stop the current timer
    }
    
    private void setTimer(boolean syn) {
        stopTimer();
        timer = new Timer(); // start a new one if necessary
        if (syn)
            timer.schedule(new SYNTimeout(),  Utils.synTimeout);
        else
            timer.schedule(new DataTimeout(), Utils.dataTimeout);
    }
    
    /**
     *  Establishing Connection
     */
    
    private class SYNTimeout extends TimerTask {
        public void run() {
            sendSYN();
        }
    }
    
    private boolean sendSYN() {
        if (synAttempts > Utils.synAttempts)
            return false;
        
        logger.info("Sending SYN " + synAttempts + " to (" + dstAddr.toString() + ", " + dstPort + ")");
        TCPPacket synPacket = new TCPPacket(recvPort, dstPort, nextSeqNum, cwnd);
        synPacket.setSYN();
        
        MSTCPInformation msInfo = requester.mstcpInformation;
        synPacket.setData(msInfo.bytes());
        synPacket.setTime_req();
        
        byte[] synBytes = synPacket.bytes();
        try {
            outSocket.send(new DatagramPacket(synBytes, synBytes.length, dstAddr, dstPort));
        } catch (IOException e) {
            logger.log(Level.SEVERE, e.getMessage(), e);
            return false;
        }
        synAttempts++;
        setTimer(true);
        return true;
        
    }
    
    private boolean establishConnection() {
        byte[] inData = new byte[Utils.pktSize];
        DatagramPacket inPkt = new DatagramPacket(inData, inData.length);
        
        if (sendSYN()) {
            for (;;) {
                try {
                    inSocket.receive(inPkt);
                } catch (IOException e) {
                    logger.log(Level.SEVERE, e.getMessage(), e);
                    System.exit(1);
                }
                
                if (Utils.delayAndDrop(logger))
                    continue;
                
                time_recv = (int) System.currentTimeMillis() % Integer.MAX_VALUE;
                TCPPacket tcpPacket = new TCPPacket(inData);
                if (tcpPacket.verifyChecksum() && tcpPacket.isSYN() && tcpPacket.isACK() && tcpPacket.getACK() == initialSeqNum) {
                    stopTimer();
                    time_ack = time_recv - tcpPacket.getTime_ack();
                    if (time_ack < 0)
                        time_ack = time_recv + Integer.MAX_VALUE - tcpPacket.getTime_ack();
                    base_rtt = time_ack + tcpPacket.getTime_req();
                    logger.info("Received SYN + ACK. RTT: " + base_rtt);
                    requester.mstcpInformation.update(new MSTCPInformation(tcpPacket.getData()));
                    return true;
                }
                
            }
        }
        return false;
    }
    
    /**
     * Receiving ACKs + Data
     * @throws InterruptedException 
     */
    
    private void retransmit() throws InterruptedException {
        if (toRetransmit.contains(sentRequests.getFirst()))
            return;
        
        synchronized (equilibrium_rate) {
            equilibrium_rate = 0.0;
            queue_delay = 0;
        }
        
        // prepare for next round
        rttSeqNum = nextSeqNum;
        sampled_num = 0;
        sampled_rtt = 0;
        toRetransmit.put(sentRequests.getFirst());
        initialSeqNum.notifyAll();
    }
    
    private class DataTimeout extends TimerTask {
        public void run() {
            try {
                retransmit();
            } catch (InterruptedException e) {
                logger.log(Level.SEVERE, e.getMessage(), e);
                System.exit(1);
            }
        }
    }   
    
    public class InThread extends Thread {
        public void run() {
            byte[] inData = new byte[Utils.pktSize];
            DatagramPacket data = new DatagramPacket(inData, inData.length);
            
            try {
                for (;;) {
                    logger.info("Waiting for next ACK + Data");
                    inSocket.receive(data);
                    if (Utils.delayAndDrop(logger))
                        continue;
                    time_recv = (int) (System.currentTimeMillis() % Integer.MAX_VALUE);
                    
                    if (requester.transfer_complete)
                        break;
                    
                    TCPPacket tcpPacket = new TCPPacket(inData);
                    
                    if (tcpPacket.verifyChecksum() && tcpPacket.isACK() && base <= tcpPacket.getSeqNum()) { // if packet is corrupted there's not much we can do...
    
                        if (tcpPacket.getACK() < tcpPacket.getSeqNum()) { // duplicate ACK, may need to retransmit
                            int timeout = sentRequests.getFirst().getTime_req() + Utils.dataTimeout;
                            if (timeout < 0)
                                timeout = Utils.dataTimeout - (Integer.MAX_VALUE - sentRequests.getFirst().getTime_req());
                            if ((System.currentTimeMillis() % Integer.MAX_VALUE) > timeout)
                                retransmit();
                        }
                        
                        sentRequests.remove(tcpPacket);
                        
                        time_ack = time_recv - tcpPacket.getTime_ack();
                        if (time_ack < 0)
                            time_ack = time_recv + Integer.MAX_VALUE - tcpPacket.getTime_ack();
                        long pkt_rtt = time_ack + tcpPacket.getTime_req();
                        if (pkt_rtt < base_rtt)
                            base_rtt = pkt_rtt;
                        sampled_num++;
                        sampled_rtt += pkt_rtt;
                        
                        if (tcpPacket.getSeqNum() == rttSeqNum) { // end of a round: do congestion control
                            // average RTT on the last round
                            rtt = ((double) sampled_rtt) / sampled_num;
                            diff = cwnd_true * ( ( rtt - base_rtt ) / rtt );
                            
                            // tweak weights and alphas
                            if (diff >= alpha) {
                                synchronized (requester.total_rate) {
                                    requester.total_rate -= equilibrium_rate;
                                    synchronized (equilibrium_rate) {
                                        equilibrium_rate = cwnd_true / rtt;
                                    }
                                    requester.total_rate += equilibrium_rate;
                                }
                                
                                requester.adjust_weights();
                                alpha = weight * requester.total_alpha;
                                if (alpha < 2) // lower bound
                                    alpha = 2;
                            }
                            
                            // window adjustment
                            if (diff < alpha)
                                cwnd_true++;
                            else
                                cwnd_true--;
                            
                            // try to drain queues if needed
                            q = rtt - base_rtt;
                            synchronized (equilibrium_rate) {
                                if (queue_delay == 0 || queue_delay > q)
                                    queue_delay = q;
                                
                                if (q > 2 * queue_delay) {
                                    backoff_factor = 0.5 * (base_rtt / rtt);
                                    cwnd_true = cwnd_true * backoff_factor;
                                    queue_delay = 0;
                                }
                            }
                            
                            if (cwnd_true < 2)
                                cwnd_true = 2;
                            
                            cwnd = (int) cwnd_true;
                            
                            logger.info("Done Congestion Control. Congestion Window is now: " + cwnd);
                            
                            // prepare for next round
                            rttSeqNum = nextSeqNum;
                            sampled_num = 0;
                            sampled_rtt = 0;
                        }
                        
                        synchronized (initialSeqNum) {
                            base = tcpPacket.getACK();
                            if (base == nextSeqNum)
                                stopTimer(); // no outstanding requests so stop timer
                            else
                                setTimer(false); // otherwise start waiting for next response
                            initialSeqNum.notifyAll();
                        }
                        
                        logger.info("InThread: Received Block from (" + dstAddr + ", " + dstPort + ")");
                        // pass data to receiver
                        receivedData.put(tcpPacket);
                    } else
                        logger.info("InThread: Received Corrputed Packet");
                }
            } catch (IOException | InterruptedException e) {
                logger.log(Level.SEVERE, e.getMessage(), e);
                System.exit(1);
            }
        }
    }
    
    
    /**
     * Sending Requests
     */
    public class OutThread extends Thread {
        public void run() {
            TCPPacket tcpRequest;
            byte[] request;
            
            try {
                while (!requester.transfer_complete) {
                    if (toRetransmit.size() > 0) {
                        tcpRequest = toRetransmit.remove();
                        tcpRequest.setTime_req();
                        request = tcpRequest.bytes();
                    } else {
                        synchronized(initialSeqNum) {
                            if (nextSeqNum >= base + cwnd) {
                                initialSeqNum.wait();
                                continue;
                            }
                        }
                        
                        int blockToRequest = requester.blockToRequest(recvPort);
                        ByteBuffer bb = ByteBuffer.allocate(4); // space for an Integer
                        bb.putInt(blockToRequest);
                        logger.info("Requesting Block " + blockToRequest);
                        tcpRequest = new TCPPacket(recvPort, dstPort, nextSeqNum, cwnd, bb.array());
                        if (nextSeqNum == initialSeqNum)
                            tcpRequest.setACK(initialSeqNum);
                        tcpRequest.setTime_req();
                        sentRequests.add(tcpRequest);
                        
                        logger.info("Sending request to (" + dstAddr + ", " + dstPort + ")");
                        request = tcpRequest.bytes();
                        synchronized(initialSeqNum) {
                            nextSeqNum++;
                        }
                    }
                    outSocket.send(new DatagramPacket(request, request.length, dstAddr, dstPort));
                }
            } catch(IOException | InterruptedException e) {
                logger.log(Level.SEVERE, e.getMessage(), e);
                System.exit(1);
            }
        }
    }
    
    
    /**
     * Thread Operation
     */
    
    public void close() {
        logger.info("Closing Connection.");
        // TODO: FIN, FIN+ACK, ACK routine
        inSocket.close();
        outSocket.close();
    }
    
    public void run() {
        InThread inThread = new InThread();
        OutThread outThread = new OutThread();
        
        if (establishConnection()) {
            inThread.start();
            outThread.start();
        } else
            logger.warning("Unable to Start Connection to (" + dstAddr.toString() + ", " + dstPort + ")");
    }
    
    
    public MSTCPRequesterConnection(String addr, int recvPort, int dstPort, MSTCPRequester requester, boolean ms_join) throws UnknownHostException, SocketException {
        logger = Utils.getLogger(this.getClass().getName() + "_" + recvPort);

        this.dstAddr = InetAddress.getByName(addr);
        this.recvPort = recvPort;
        this.dstPort = dstPort;
        this.requester = requester;
        this.inSocket = new MSTCPSocket(new DatagramSocket(recvPort));
        this.outSocket = new MSTCPSocket(new DatagramSocket());
        
        this.sentRequests = new LinkedList<TCPPacket>();
        this.receivedData = new LinkedBlockingQueue<TCPPacket>();
        this.toRetransmit = new LinkedBlockingQueue<TCPPacket>();
        
        if (!ms_join)
            this.run();
        else
            this.start();
        
        logger.info("Started up connection on port " + recvPort);
    }
}
