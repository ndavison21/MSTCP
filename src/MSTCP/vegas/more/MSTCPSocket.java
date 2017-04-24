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
    private LinkedBlockingQueue<DatagramPacket> inBuffer = new LinkedBlockingQueue<DatagramPacket>();
    private LinkedBlockingQueue<DatagramPacket> outBuffer = new LinkedBlockingQueue<DatagramPacket>();


    private class Receiver extends Thread {
        public void run() {
            try {
                for (;;) {
                    DatagramPacket d = new DatagramPacket(new byte[Utils.pktSize], Utils.pktSize);
                    inSocket.receive(d);
                    inBuffer.add(d);
                }
            } catch (IOException e) {
                if (e instanceof SocketException && inSocket.isClosed()) {
                    return;
                }
                
                logger.log(Level.SEVERE, e.getMessage(), e);
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
                    if (d.getData()[0] == -1 && d.getLength() == 0) { // 'poison pill' shutdown
                        synchronized(outBuffer) {
                            outBuffer.clear();
                            outBuffer.notifyAll();
                        }
                        return;
                    } 
                    if (Utils.drop()) {
                        System.out.println("Dropping packet ");
                        continue;
                    }
                    Utils.delay(logger);
                    outSocket.send(d);
                }
            } catch (InterruptedException e) {
                logger.log(Level.SEVERE, e.getMessage(), e);
                System.exit(1);
            } catch (IOException e) {
                logger.log(Level.SEVERE, e.getMessage(), e);
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
                System.exit(1);
            }
        }
    }
    
    public MSTCPSocket(Logger logger) throws SocketException { // connect to any free port
        this(logger, -1);
    }

    public MSTCPSocket(Logger logger, int port) throws SocketException {
        this.logger    = logger;
        this.inSocket  = port == -1 ? new DatagramSocket() : new DatagramSocket(port);
        this.outSocket = new DatagramSocket();
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

    // @Override
    // public void interrupt() {
    // receiver.interrupt();
    // super.interrupt();
    // }
}
