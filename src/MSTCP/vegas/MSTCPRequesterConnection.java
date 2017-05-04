package MSTCP.vegas;

import java.io.IOException;
import java.net.DatagramPacket;
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
    
    final MSTCPSocket socket;
    final InThread inThread;
    final OutThread outThread;
    
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
    
    /**
     * 0: Data
     * 1: SYN
     * 2: FIN
     */
    private void setTimer(int type) {
        stopTimer();
        timer = new Timer(); // start a new one if necessary
        if (type == Utils.DATA_ENUM)
            timer.schedule(new DataTimeout(),  Utils.dataTimeout);
        else if (type == Utils.SYN_ENUM)
            timer.schedule(new SYNTimeout(), Utils.synTimeout);
        else if (type == Utils.FIN_ENUM)
            timer.schedule(new FINTimeout(), Utils.finTimeout);
    }
    
    /**
     *  Connection Establishment / Teardown
     */
    
    private class FINTimeout extends TimerTask {
        public void run() {
            sendFIN(false);
        }
    }
    
    public void sendFIN(boolean ack) {
        int rpt = 1;
        TCPPacket fin = new TCPPacket(recvPort, dstPort, nextSeqNum, cwnd);
        fin.setFIN();
        if (ack) {
            rpt = 3; // send it a few times to make sure it gets there
            fin.setACK(nextSeqNum);
            logger.info("Received FIN + ACK. Sending ACK to (" + dstAddr + ", " + dstPort + ")");
        } else {
            logger.info("Sending FIN to (" + dstAddr + ", " + dstPort + ")");
            setTimer(Utils.FIN_ENUM);
        }
        byte[] finBytes = fin.bytes();
        
        try {
            for(int i=0; i<rpt; i++)
                socket.send(new DatagramPacket(finBytes, finBytes.length, dstAddr, dstPort));
        } catch (IOException e) {
            logger.log(Level.SEVERE, e.getMessage(), e);
            e.printStackTrace();
            System.exit(1);
        }
    }
    
    public void close() {
        logger.info("Closing Connection to (" + dstAddr + ", " + dstPort + ")");
        
        inThread.interrupt();
        outThread.interrupt();
        
        new Thread() {
            public void run() {
                TCPPacket in;
                DatagramPacket inPkt;
                
                sendFIN(false);
                
                for (;;) {
                    inPkt = socket.receive();
                    in = new TCPPacket(inPkt.getData());
                    
                    if (in.verifyChecksum() && in.isACK() && in.getSeqNum() == nextSeqNum)
                        break;
                }
                
                stopTimer();
                sendFIN(true);
                
                socket.close();
                logger.info("We Done Here.");
            }
        }.start();

    }  
    
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
            socket.send(new DatagramPacket(synBytes, synBytes.length, dstAddr, dstPort));
        } catch (IOException e) {
            logger.log(Level.SEVERE, e.getMessage(), e);
            e.printStackTrace();
            return false;
        }
        synAttempts++;
        setTimer(Utils.SYN_ENUM);
        return true;
        
    }
    
    private boolean establishConnection() {
        DatagramPacket inPkt;
        
        if (sendSYN()) {
            for (;;) {
                inPkt = socket.receive();
                
                time_recv = (int) System.currentTimeMillis() % Integer.MAX_VALUE;
                TCPPacket tcpPacket = new TCPPacket(inPkt.getData());
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
        if (toRetransmit.contains(sentRequests.peek()))
            return;
        
        logger.info("Retransmitting packet " + sentRequests.peek().getSeqNum());
        synchronized (equilibrium_rate) {
            equilibrium_rate = 0.0;
            queue_delay = 0;
        }
        
        // prepare for next round
        rttSeqNum = nextSeqNum;
        sampled_num = 0;
        sampled_rtt = 0;
        toRetransmit.put(sentRequests.peek());
        initialSeqNum.notifyAll();
    }
    
    private class DataTimeout extends TimerTask {
        public void run() {
            try {
                retransmit();
            } catch (InterruptedException e) {
                logger.log(Level.SEVERE, e.getMessage(), e);
                e.printStackTrace();
                System.exit(1);
            }
        }
    }   
    
    public class InThread extends Thread {
        public void run() {
            DatagramPacket data;
            
            try {
                for (;;) {
                    logger.info("Waiting for next ACK + Data");
                    data = socket.receive();
                    if (this.isInterrupted())
                        return;
                    time_recv = (int) (System.currentTimeMillis() % Integer.MAX_VALUE);
                    
                    if (requester.transfer_complete)
                        break;
                    
                    TCPPacket tcpPacket = new TCPPacket(data.getData());
                    
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
                            
                            logger.info("Congestion Window is now " + cwnd + " (" + cwnd_true + ")");
                            
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
                                setTimer(Utils.DATA_ENUM); // otherwise start waiting for next response
                            initialSeqNum.notifyAll();
                        }
                        
                        logger.info("Received Block from (" + dstAddr + ", " + dstPort + ")");
                        // pass data to receiver
                        receivedData.put(tcpPacket);
                    } else
                        logger.info("Received Corrputed Packet");
                }
            } catch (InterruptedException e) {
                logger.log(Level.SEVERE, e.getMessage(), e);
                e.printStackTrace();
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
                        if (blockToRequest == -1) {
                            this.interrupt();
                            return;
                        }
                        ByteBuffer bb = ByteBuffer.allocate(4); // space for an Integer
                        bb.putInt(blockToRequest);
                        tcpRequest = new TCPPacket(recvPort, dstPort, nextSeqNum, cwnd, bb.array());
                        if (nextSeqNum == initialSeqNum)
                            tcpRequest.setACK(initialSeqNum);
                        tcpRequest.setTime_req();
                        sentRequests.add(tcpRequest);
                        
                        logger.info("Sending request for block " + blockToRequest + " to (" + dstAddr + ", " + dstPort + ")");
                        request = tcpRequest.bytes();
                        synchronized(initialSeqNum) {
                            nextSeqNum++;
                        }
                    }
                    socket.send(new DatagramPacket(request, request.length, dstAddr, dstPort));
                }
            } catch(IOException e) {
                logger.log(Level.SEVERE, e.getMessage(), e);
                e.printStackTrace();
                System.exit(1);
            } catch (InterruptedException e) {
                logger.log(Level.WARNING, "OutThread Interrupted. " + e.getMessage(), e);
                return;
            }
        }
    }
    
    
    /**
     * Main Methods
     */
    public void run() {
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
        this.socket = new MSTCPSocket(logger, recvPort);
        
        this.sentRequests = new LinkedList<TCPPacket>();
        this.receivedData = new LinkedBlockingQueue<TCPPacket>();
        this.toRetransmit = new LinkedBlockingQueue<TCPPacket>();
        
        this.inThread = new InThread();
        this.outThread = new OutThread();
        
        if (!ms_join)
            this.run();
        else
            this.start();
        
        logger.info("Started up connection on port " + recvPort);
    }
}
