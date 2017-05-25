package MSTCP.vegas.more;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.logging.Level;
import java.util.logging.Logger;

public class MSTCPSocket {
    private final Logger logger;
    private final DatagramSocket inSocket;
    private final DatagramSocket outSocket;
    private final Receiver receiver;
    private final Sender sender;
    
    private final int delay;
    private final double p_drop;
    
    boolean droppedPrev = false;
    
    private LinkedBlockingQueue<DatagramPacket> inBuffer = new LinkedBlockingQueue<DatagramPacket>();
    private LinkedBlockingQueue<DatagramPacket> outBuffer = new LinkedBlockingQueue<DatagramPacket>();


    private class Receiver extends Thread {
        public void run() {
            try {
                for (;;) {
                    final DatagramPacket d = new DatagramPacket(new byte[Utils.pktSize()], Utils.pktSize());
                    inSocket.receive(d);
                    if (inBuffer.size() <= Utils.queueCapacity)
                        inBuffer.offer(d);
                }
            } catch (IOException e) {
                if (e instanceof SocketException && inSocket.isClosed()) {
                    return;
                }
                
                logger.log(Level.SEVERE, e.getMessage(), e);
                e.printStackTrace();
                System.exit(1);
            }
        }
    }
    
    
    private class Sender extends Thread {
        public void run() {
            DatagramPacket d;
            try {
                for (;;) {
                    d = outBuffer.take();
                    if (d.getData() != null && d.getLength() < d.getData().length && d.getData()[0] == -1) { // 'poison pill' shutdown
                        synchronized(outBuffer) {
                            outBuffer.clear();
                            outBuffer.notifyAll();
                        }
                        return;
                    }
                    
                    if (Utils.rand.nextDouble() < p_drop){
                        logger.info("Packet Dropped");
                        continue;
                    }
                    
                    if (delay > 0) {
                        Thread.sleep(delay / (outBuffer.size() + 1));
                    }
                    outSocket.send(d);
                }
            } catch (InterruptedException e) {
                logger.log(Level.SEVERE, e.getMessage(), e);
                e.printStackTrace();
                System.exit(1);
            } catch (IOException e) {
                logger.log(Level.SEVERE, e.getMessage(), e);
                e.printStackTrace();
                System.exit(1);
            }
        }
        
        @Override
        public void interrupt() {
            try {
                synchronized(outBuffer) {
                    outBuffer.add(new DatagramPacket(new byte[]{-1}, 0)); // 'poison pill' shutdown
                    outBuffer.wait();
                }
                super.interrupt();
            } catch (InterruptedException e) {
                logger.log(Level.SEVERE, e.getMessage(), e);
                e.printStackTrace();
                System.exit(1);
            }
        }
    }
    
    
    public MSTCPSocket(Logger logger, int port) throws SocketException { // connect to any free port
        this(logger, port, 0, 0);
    }

    
    
    public MSTCPSocket(Logger logger, int port, int delay, double p_drop) throws SocketException {
        this.logger    = logger;
        this.inSocket  = port == -1 ? new DatagramSocket() : new DatagramSocket(port);
        this.outSocket = new DatagramSocket();
        this.delay = delay;
        this.p_drop = p_drop;
        
        this.receiver  = new Receiver();
        this.sender    = new Sender();
        
        sender.start();
        receiver.start();
    }

    public int getLocalPort() {
        return inSocket.getLocalPort();
    }

    public void send(DatagramPacket d) throws IOException {
        outBuffer.add(d);
    }

    public DatagramPacket receive() {
        DatagramPacket d = null;
        try {
            d = inBuffer.take();
        } catch (InterruptedException e) {
            logger.warning("Socket Interrupted");
        }
        return d;
    }

    public void close() {
        try {
            receiver.interrupt();
            sender.interrupt();
        } finally {
            inSocket.close();
            outSocket.close();
        }
    }
    
    public boolean isClosed() {
        return inSocket.isClosed() && outSocket.isClosed();
    }

    // @Override
    // public void interrupt() {
    // receiver.interrupt();
    // super.interrupt();
    // }
}
