package MSTCP.vegas.more;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.Logger;

public class MSTCPRequesterConnection extends Thread {
    final Logger logger;
    
    final int recvPort;
    final InetAddress dstAddr;
    final int routerPort;
    final int dstPort;
    MSTCPRequester requester;
    
    final MSTCPSocket socket;
    final InThread inThread;
    final OutThread outThread;
    
    ConcurrentHashMap<Integer, TCPPacket> sentRequests;
    
    Timer timer;
    int synAttempts = 0;
    int dataAttempts = 0;
    int finAttempts = 0;
    
    // rtt measurements
    int time_recv    = -1;
    int time_ack     = -1;
    long base_rtt    = -1; // lowest RTT measured (i.e. when no congestion)
    int pkt_rtt      = -1; // instantaneous rtt
    long sampled_rtt =  0; // sum of RTTs in the current round
    int sampled_num  =  0; // number of RTTs in the current round
    double rtt       = -1; // average RTT of the previous round
    double diff      = -1; // difference of previous RTT to the base rtt (i.e. measure of congestion)
    
    // congestion control
    boolean slowstart  = true; // TODO: Implement slow start
    double ssthresh    = Integer.MAX_VALUE;
    int cwnd           = 2;
    double cwnd_true   = 2.0;
    double alpha       = 2;
    double weight      = 0;
    double q           = -1;
    double queue_delay = 0;
    double backoff_factor   = -1;
    Double equilibrium_rate = 0.0;
    
    // tracking whether connection is still active
    public Bool active = new Bool(true);
    // public Bool startup = new Bool(true);
    
    // calculating redundancy to send
    double p_drop = 1.0/3.0; // approximate at 1/3 to begin with
    
    // fast retransmit
    ConcurrentLinkedQueue<Integer> toRetransmit; // sequence number of packets to retransmit
    
    
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
        try {
            if (!ack && finAttempts > Utils.finAttempts) {
                for (int i=0; i<20; i++)
                    socket.send(new DatagramPacket(new byte[0], 0, requester.recvAddr, this.recvPort));
                return;
            }
            int rpt = 1;
            MOREPacket more = new MOREPacket(requester.mstcpInformation.flowID, MOREPacket.FORWARD_PACKET);
            TCPPacket fin = new TCPPacket(recvPort, dstPort, nextSeqNum, cwnd, more.bytes());
            fin.setFIN();
            if (ack) {
                rpt = 20; // send it a few times to make sure it gets there
                fin.setACK(nextSeqNum);
                logger.info("Received FIN + ACK. Sending ACK to (" + dstAddr + ", " + dstPort + ")");
            } else {
                logger.info("Sending FIN to (" + dstAddr + ", " + dstPort + ")");
                setTimer(Utils.FIN_ENUM);
                finAttempts++;
            }
            byte[] finBytes = fin.bytes();
        
            for(int i=0; i<rpt; i++)
                socket.send(new DatagramPacket(finBytes, finBytes.length, dstAddr, routerPort));
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
        stopTimer();
        
        try {
            TCPPacket in;
            DatagramPacket inPkt;
            
            sendFIN(false);
            
            boolean ack = false;
            for (;;) {
                inPkt = socket.receive();
                
                synchronized(requester.total_rate) {
                    requester.total_rate -= equilibrium_rate;
                    equilibrium_rate = 0.0;
                    queue_delay = 0;
                }
                
                if (inPkt.getData() != null && inPkt.getLength() == 0)
                    break;
                
                in = new TCPPacket(inPkt.getData());
                
                if (in.verifyChecksum() && in.isACK() && in.getSeqNum() == nextSeqNum) {
                    ack = true;
                    break;
                }
            }
            
            stopTimer();
            if (ack)
                sendFIN(true);
            
//            active.b = false;
//            active.notifyAll();
        
        } finally {
            synchronized(socket) {
                socket.close();
                socket.notifyAll();
            }
            logger.info("We Done Here.");
            for (Handler handler: logger.getHandlers())
                handler.close();
        }

    }  
    
    private class SYNTimeout extends TimerTask {
        public void run() {
            sendSYN();
        }
    }
    
    private void sendSYN() {
        try {
            if (synAttempts > Utils.synAttempts) {
                    logger.info("SYN limit reached. Giving up.");
                    socket.send(new DatagramPacket(new byte[]{0}, 0, requester.recvAddr, this.recvPort));
                    return;
            }
                
            logger.info("Sending SYN " + synAttempts + " to (" + dstAddr.toString() + ", " + dstPort + ")");
            TCPPacket synPacket = new TCPPacket(recvPort, dstPort, nextSeqNum, cwnd);
            synPacket.setSYN();
            
            MSTCPInformation msInfo = requester.mstcpInformation;
            msInfo.recvPort = this.recvPort;
            synPacket.setData(msInfo.bytes());
            synPacket.setTime_req();
            
            byte[] synBytes = synPacket.bytes();
            socket.send(new DatagramPacket(synBytes, synBytes.length, dstAddr, routerPort));
        } catch (IOException e) {
            logger.log(Level.SEVERE, e.getMessage(), e);
            e.printStackTrace();
            System.exit(1);
        }
        synAttempts++;
        setTimer(Utils.SYN_ENUM);        
    }
    
    private boolean establishConnection() {
        DatagramPacket inPkt;
        boolean success = false;
        
        sendSYN();
        
        for (;;) {
            inPkt = socket.receive();
            
            time_recv = (int) (System.currentTimeMillis() % Integer.MAX_VALUE);
            TCPPacket tcpPacket = new TCPPacket(inPkt.getData());
            if (inPkt.getData() != null && inPkt.getLength() == 0) { // poison pill shutdown
//                try {
//                    socket.send(inPkt); // poison pill shutdown
//                    this.active.b = false;
//                } catch (IOException e) {
//                    logger.log(Level.SEVERE, e.getMessage(), e);
//                    e.printStackTrace();
//                    System.exit(1);
//                }
                break;
            }
            
            if (tcpPacket.verifyChecksum() && tcpPacket.isSYN() && tcpPacket.isACK() && tcpPacket.getACK() == initialSeqNum) {
                stopTimer();
                time_ack = time_recv - tcpPacket.getTime_ack();
                if (time_ack < 0)
                    time_ack = time_recv + Integer.MAX_VALUE - tcpPacket.getTime_ack();
                base_rtt = time_ack + tcpPacket.getTime_req();
                logger.info("Received SYN + ACK. RTT: " + base_rtt);
                requester.mstcpInformation.update(new MSTCPInformation(tcpPacket.getData()));
                if (requester.sourceCoder == null) {
                	requester.sourceCoder = new SourceCoder(requester.logger, requester.mstcpInformation.fileSize);
                	
                	requester.nextBatchReqs = Math.min(Utils.batchSize, requester.sourceCoder.fileBlocks);
                }
                success = true;
                break;
            }
            
        }
        
        return success;
    }
    
    /**
     * Receiving ACKs + Data
     * @throws InterruptedException 
     */
    
    private void retransmit(Integer seqNum) throws InterruptedException {
        if (toRetransmit.contains(seqNum))
            return;
        else
            toRetransmit.add(seqNum);
        
        logger.info("Retransmitting packet " + seqNum);
        synchronized (equilibrium_rate) {
            synchronized(requester.total_rate) {
                requester.total_rate -= equilibrium_rate;
                equilibrium_rate = 0.0;
                queue_delay = 0;
            }
        }
        synchronized (initialSeqNum) {
            initialSeqNum.notifyAll();
        }
    }
    
    private void goBackN() {
        if (dataAttempts > Utils.dataAttempts) {
            synchronized(active) {
                logger.info("Data limit reached. Giving up.");
                active.b = false;
                active.notifyAll();
            }
            return;
        }
        
        logger.info("Data Time Out. Resending all packets.");

        synchronized (equilibrium_rate) {
            synchronized(requester.total_rate) {
                requester.total_rate -= equilibrium_rate;
                equilibrium_rate = 0.0;
                queue_delay = 0;
            }
        }
        
        toRetransmit.clear();
        sentRequests.clear();   
        
        synchronized(initialSeqNum) {
            cwnd_true = 2;
            cwnd = (int) cwnd_true;
            slowstart = true;
            rttSeqNum = nextSeqNum + cwnd;
            sampled_num = 0;
            sampled_rtt = 0;
            
            nextSeqNum = base;
            initialSeqNum.notifyAll();
        }
        //setTimer(Utils.DATA_ENUM);
        dataAttempts++;
    }
    
    private class DataTimeout extends TimerTask {
        public void run() {
            goBackN();
        }
    }   
    
    public class InThread extends Thread {
        public void run() {
            DatagramPacket data;
            
            try {
                for (;;) {
//                    logger.info("Waiting for next ACK + Data");
                    data = socket.receive();
                    if (this.isInterrupted() || data == null)
                        return;
                    time_recv = (int) (System.currentTimeMillis() % Integer.MAX_VALUE);
                    
                    if (requester.transfer_complete)
                        break;
                    
                    TCPPacket tcpPacket = new TCPPacket(data.getData());
                    
                    if (tcpPacket.verifyChecksum() && tcpPacket.isACK() && base <= tcpPacket.getSeqNum()) { // if packet is corrupted there's not much we can do...
                        dataAttempts = 0;
                        double mult = 1.0;
                        
                        
                        if (tcpPacket.getACK() < tcpPacket.getSeqNum() || tcpPacket.getSeqNum() > base) {
                            int retransmit;
                            if (tcpPacket.getACK() < tcpPacket.getSeqNum()) {
                                retransmit = tcpPacket.getACK();
                            } else {
                                retransmit = base;
                            }
                            double timeout = false ? Integer.MAX_VALUE : sentRequests.get(retransmit).getTime_req() + (2 * rtt);
                            if (timeout < 0)
                                timeout = (2 * rtt) - (Integer.MAX_VALUE - sentRequests.get(retransmit).getTime_req());
                            
                            if ((System.currentTimeMillis() % Integer.MAX_VALUE) > timeout) {
                                mult = 1.0 - Utils.p_smooth;
                                retransmit(retransmit);
                            }
                                
                        }
                        
                        
                        if (tcpPacket.getSeqNum() == base) { // packet received in order
                            base = tcpPacket.getACK();
                        }

                        sentRequests.remove(tcpPacket);
                        if (base == nextSeqNum)
                            stopTimer(); // no outstanding requests so stop timer
                        else
                            setTimer(Utils.DATA_ENUM); // otherwise start waiting for next response

                        
                        p_drop = ( p_drop * mult * (1 - Utils.p_smooth) ) + (1 - mult);
                        p_drop = Math.min(1, p_drop);
                        System.out.println(p_drop);
                        
                        time_ack = time_recv - tcpPacket.getTime_ack();
                        if (time_ack < 0)
                            time_ack = time_recv + Integer.MAX_VALUE - tcpPacket.getTime_ack();
                        pkt_rtt = time_ack + tcpPacket.getTime_req();
                        
                        synchronized (initialSeqNum) {
                            if (pkt_rtt < base_rtt)
                                base_rtt = pkt_rtt;
                            
                            sampled_num++;
                            sampled_rtt += pkt_rtt;
                        
                            if (tcpPacket.getSeqNum() == rttSeqNum) { // end of a round: do congestion control
                                // average RTT on the last round
                                rtt = ((double) sampled_rtt) / sampled_num;
                                diff = cwnd_true * ((rtt - base_rtt) / rtt);
                                                                
                                if (slowstart) {
                                    cwnd_true += sampled_num;
                                    if (cwnd_true >= ssthresh || diff > Utils.gamma) {
                                        logger.info("Entering Congestion Avoidance Phase.");
                                        slowstart = false;
                                        if (diff > Utils.gamma)
                                            ssthresh = cwnd_true - 1;
                                        else
                                            cwnd_true = ssthresh;
                                    }
                                } else {
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
                                        alpha = Math.max(2, weight * requester.total_alpha);
                                    }
                                    
                                    // window adjustment
                                    if (diff < alpha)
                                        cwnd_true++;
                                    else if (diff > alpha)
                                        cwnd_true--;
                                    
                                    // try to drain queues if needed
                                    q = rtt - base_rtt;
                                    synchronized (equilibrium_rate) {
                                        if (queue_delay == 0 || queue_delay > q)
                                            queue_delay = q;
                                        
                                        if (q >= 2 * queue_delay) {
                                            backoff_factor = 0.5 * (base_rtt / rtt);
                                            cwnd_true = cwnd_true * backoff_factor;
                                            queue_delay = 0;
                                        }
                                    }
                                }
                                
                                if (cwnd_true < 2)
                                    cwnd_true = 2;
                                
                                cwnd = (int) cwnd_true;
                                
                                logger.info("Congestion Window is now " + cwnd + " (" + cwnd_true + ")");
                                
                                // prepare for next round
                                rttSeqNum = nextSeqNum + cwnd;
                                sampled_num = 0;
                                sampled_rtt = 0;
                            }
                            initialSeqNum.notifyAll();
                        }
                        
                        logger.info("Received Batch from (" + dstAddr + ", " + dstPort + ")");
                        // pass data to receiver
                        if (!requester.transfer_complete)
                            requester.sourceCoder.receivedPackets.put(new MOREPacket(tcpPacket.getData()));
                    } else {
                        if (base <= tcpPacket.getSeqNum()) {
                            logger.info("Received Packet Out of Order (seqNum " + tcpPacket.getSeqNum() + ", base " + base + ". Passing to Requester anyway.");
                            requester.sourceCoder.receivedPackets.put(new MOREPacket(tcpPacket.getData()));
                        } else
                            logger.info("Received Corrputed Packet");
                    }
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
            CodeVectorElement[] codeVector;
            
            try {
                while (!requester.transfer_complete) {
                    if (toRetransmit.isEmpty()) {
                        synchronized(initialSeqNum) {
                            if (nextSeqNum >= base + cwnd) {
                                logger.info("Congestion window full. Waiting.");
                                initialSeqNum.wait();
                            }
                        }
                    }
                    
                    codeVector = requester.codeVector(recvPort, p_drop);
                    if (codeVector == null) {
                        logger.info("Got all data, waiting for decode.");
                        stopTimer();
                        this.interrupt();
                        return;
                    }
                    
                    short batch = (short) (codeVector[0].getBlock() / Utils.batchSize);
                    
                    MOREPacket more = new MOREPacket(requester.mstcpInformation.flowID, MOREPacket.FORWARD_PACKET, batch, codeVector);;
                    
                    int seqNum = (toRetransmit.isEmpty() ? nextSeqNum : toRetransmit.poll());
                    
                    tcpRequest = new TCPPacket(recvPort, dstPort, seqNum, cwnd);
                    tcpRequest.setData(more.bytes());
                    if (nextSeqNum == base) {
                        setTimer(Utils.DATA_ENUM);
                        if (nextSeqNum == initialSeqNum)
                            tcpRequest.setACK(initialSeqNum);
                    }
                    tcpRequest.setTime_req();
                    sentRequests.put(tcpRequest.getSeqNum(), tcpRequest);
                    
                    logger.info("Sending request for batch " + batch + " to (" + dstAddr + ", " + dstPort + ")");
                    request = tcpRequest.bytes();
                    if (seqNum == nextSeqNum) {
                        synchronized(initialSeqNum) {
                            nextSeqNum++;
                        }
                    }

                    socket.send(new DatagramPacket(request, request.length, dstAddr, routerPort));
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
        //synchronized(startup) {
            if (establishConnection()) {
                inThread.start();
                outThread.start();
            } else {
                logger.warning("Unable to Start Connection to (" + dstAddr.toString() + ", " + dstPort + ")");
                synchronized(active) {
                    active.b = false;
                    active.notifyAll();
                }
            }
            
//            startup.b = false;
//            startup.notifyAll();
//        }
    }
    
    public MSTCPRequesterConnection(String addr, int recvPort, int dstPort, MSTCPRequester requester, boolean ms_join) throws UnknownHostException, SocketException {
        this(addr, recvPort, dstPort, dstPort, requester, ms_join);
    }
    public MSTCPRequesterConnection(String addr, int recvPort, int dstPort, int routerPort, MSTCPRequester requester, boolean ms_join) throws UnknownHostException, SocketException {
        logger = Utils.getLogger(this.getClass().getName() + "_" + recvPort);

        this.dstAddr = InetAddress.getByName(addr);
        this.recvPort = recvPort;
        this.dstPort = dstPort;
        this.routerPort = routerPort;
        this.requester = requester;
        this.socket = new MSTCPSocket(logger, recvPort);
        
        this.sentRequests = new ConcurrentHashMap<Integer, TCPPacket>();
        this.toRetransmit = new ConcurrentLinkedQueue<Integer>();
        
        this.inThread = new InThread();
        this.outThread = new OutThread();
        
        if (!ms_join)
            this.run();
        else
            this.start();
        
        logger.info("Started up connection on port " + recvPort);
    }
}
