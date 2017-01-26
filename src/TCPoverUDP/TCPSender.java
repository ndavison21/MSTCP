package TCPoverUDP;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Timer;
import java.util.TimerTask;
import java.util.Vector;
import java.util.concurrent.Semaphore;

/**
 * 
 * @author Nathanael Davison - nd359
 * 
 *      Simple implementation of Go-Back-N over UDP
 *          - Includes details of file being transferred, may be separate these into server?
 *          - No congestion control
 *              (nice to leave out this complexity for now, will need to introduced when we get to coding though)
 *          - No Handshake
 *              (TODO: need in order to exchange MSTCP details. Nice for other things like packet sizes.)
 *          - Teardown is just sending of ACK number -2
 *              (good enough for now)
 *
 */
public class TCPSender {
    static int dataSize = 100; // checksum: 8, sequNum: 4, data <= 988, so 1000 Bytes total
    static int winSize = 2;
    static int timeoutVal = 300; // ms
    
    int initialSeqNum;          // first sequence number of the connection
    int base;                   // base sequence number of the window
    int nextSeqNum;             // next sequence number in window
    String path;                // path of file to be sent
    String filename;            // filename to be saved by receiver
    Vector<byte[]> packetList;  // list of generated packets
    Timer timer;                // for timeouts
    Semaphore s;                // guard for base and nextSeqSum
    boolean transferComplete;   // if receiver has ACKed all packets of file
    
    // used to start/stop the timer
    public void setTimer(boolean newTimer) {
        if (timer != null) timer.cancel(); // stops the current timer
        if (newTimer) {
            timer = new Timer(); // start a new one if necessary
            timer.schedule(new Timeout(), timeoutVal);
        }
    }
    
    // handles the sending of data
    public class OutThread extends Thread {
        private DatagramSocket socket;
        private int dstPort;  // send to this port
        private InetAddress dstAddress;
        private int recvPort; // receive ACKs on this port. Currently unused (hardcoded at other end)
        
        public OutThread(DatagramSocket socket, int dstPort, int recvPort) {
            this.socket = socket;
            this.dstPort = dstPort;
            this.recvPort = recvPort;
            
        }
        
        // attaches a TCP header to the data bytes
        public byte[] generateTCPPacket(int seqNum, byte[] dataBytes, boolean synAck, boolean finalSeqNum) {
            TCPPacket tcpPacket = new TCPPacket(this.recvPort, this.dstPort, seqNum, TCPSender.winSize);
            if (synAck) {
                tcpPacket.setSYN();
                tcpPacket.setACK(seqNum);
            } else if (finalSeqNum) tcpPacket.setFIN();
            byte[] tcpBytes = tcpPacket.bytes();
            
            ByteBuffer packetBuffer = ByteBuffer.allocate(tcpBytes.length + dataBytes.length);
            packetBuffer.put(tcpBytes);
            packetBuffer.put(dataBytes);
            return packetBuffer.array();
        }
        
        public void sendSynAck(byte[] synAckBytes) throws IOException {
            socket.send(new DatagramPacket(synAckBytes, synAckBytes.length, dstAddress, dstPort)); // send the packet
        }
        
        // process to send packets, updating nextSeqNum
        public void run() {
            System.out.println("TCPSender: OutThread: Started");
            try {
                dstAddress = InetAddress.getByName("127.0.0.1"); // resolve the dstAddress
                FileInputStream fis = new FileInputStream(new File(path + filename));
                
                try {
                    System.out.println("TCPSender: OutThread: Beginning Sending Packets");
                    while (!transferComplete) { // while there are still packets to send
                        
                        if (nextSeqNum < base + winSize) { // if the window is not yet full, then send more packets
                            s.acquire();
                            if (base != initialSeqNum && base == nextSeqNum) setTimer(true); // if sending the first packet of the window, start timer
                            byte[] outData = new byte[10];
                            boolean finalSeqNum = false;
                            
                            if (nextSeqNum - initialSeqNum < packetList.size()) { // if packet has been constructed before (happens if we have gone-back-n)
                                outData = packetList.get(nextSeqNum - initialSeqNum);
                            } else {
                                if (nextSeqNum == initialSeqNum) { // if it's the first packet then include SYN + ACK and the file information
                                    byte[] filenameBytes = filename.getBytes();
                                    byte[] filenameLengthBytes = ByteBuffer.allocate(4).putInt(filenameBytes.length).array();
                                    byte[] dataBuffer = new byte[dataSize];
                                    int dataLength = fis.read(dataBuffer, 0, dataSize - 4 - filenameBytes.length);
                                    byte[] dataBytes = Arrays.copyOfRange(dataBuffer, 0, dataLength);
                                    ByteBuffer bb = ByteBuffer.allocate(4 + filenameBytes.length + dataBytes.length);
                                    bb.put(filenameLengthBytes);
                                    bb.put(filenameBytes);
                                    bb.put(dataBytes);
                                    outData = generateTCPPacket(nextSeqNum, bb.array(), true, finalSeqNum);
                                } else { // for subsequent packets just send data
                                    byte[] dataBuffer = new byte[dataSize];
                                    int dataLength = fis.read(dataBuffer, 0, dataSize);
                                    if (dataLength == -1) { // if no more data then send nothing (finalSeqNum)
                                        finalSeqNum = true;
                                        outData = generateTCPPacket(nextSeqNum, new byte[0], false, finalSeqNum);
                                    } else { // otherwise send the valid data
                                        byte[] dataBytes = Arrays.copyOfRange(dataBuffer, 0, dataLength);
                                        outData = generateTCPPacket(nextSeqNum, dataBytes, false, finalSeqNum);
                                    }
                                }
                                packetList.add(outData); // store so we don't have to reconstruct
                            }
                            
                            socket.send(new DatagramPacket(outData, outData.length, dstAddress, dstPort)); // send the packet
                            
                            if (!finalSeqNum) nextSeqNum++; // update nextSeqNum if it's not the last one
                            else System.out.println("TCPSender: OutThread: Reached Final Sequence Number");
                            s.release();
                        }
                        sleep(5);
                    }
                } catch (InterruptedException e) {
                    System.err.println("TCPSender: OutThread: Exception while claiming semaphore. Attempting to carry on.");
                    e.printStackTrace();
                } catch (IOException e) {
                    System.err.println("TCPSender: OutThread: Exception during communication. Attempting to carry on.");
                    e.printStackTrace();
                } finally {
                    // tidy up
                    setTimer(false); // stop timer
                    socket.close();
                    fis.close();
                }
            } catch (UnknownHostException e) {
                System.err.println("TCPSender: OutThread: Unable to resolve host");
                e.printStackTrace();
                System.exit(-1);
            } catch (FileNotFoundException e) {
                System.err.println("TCPSender: OutThread: Unable to find file " + path + filename);
                e.printStackTrace();
                System.exit(-1);
            } catch (IOException e) {
                System.err.println("TCPSender: OutThread: Exception while closing FileInputStream");
                e.printStackTrace();
                System.exit(-1);
            }
        }
    }
    
    public class InThread extends Thread {
        private DatagramSocket socket;
        private boolean connectionEstablished = false;
        
        public InThread(DatagramSocket socket) {
            this.socket = socket;
        }
        
        
        // process to receive ACKs, updating base of window as it goes along
        public void run() {
            System.out.println("TCPSender: InThread: Started");
            byte[] ackBytes = new byte[20];
            DatagramPacket ack = new DatagramPacket(ackBytes, ackBytes.length);
            
            try {
                System.out.println("TCPSender: InThread: Waiting for SYN");
                DatagramSocket outSocket = null;
                OutThread outThread = null;
                
                // receive the SYN
                while (!connectionEstablished) {
                    socket.receive(ack); // (blocks until received)
                    TCPPacket synPacket = new TCPPacket(ackBytes);
                    if (synPacket.isSYN()) {
                        System.out.println("TCPSender: InThread: SYN Received, initial sequence number " + synPacket.getSeqNum());

                        s.acquire();                        
                        // initialising sequence numbers
                        initialSeqNum = synPacket.getSeqNum();
                        base = initialSeqNum;
                        nextSeqNum = initialSeqNum;
                        connectionEstablished = true;
                        s.release();
                        
                        // Creating Thread to send data
                        outSocket = new DatagramSocket();
                        outThread = new OutThread(outSocket, synPacket.getSrcPort(), synPacket.getDestPort());
                        outThread.start();
                    }
                }
                
                
                System.out.println("TCPSender: InThread: Waiting for ACKs");
                while (!transferComplete) { // while there are still packets to be ACKed
                    socket.receive(ack);
                    
                    TCPPacket tcpPacket = new TCPPacket(ackBytes);
                    System.out.println("TCPSender: InThread: Received TCP ACK: " + tcpPacket.getACK());
                    if (tcpPacket.isACK() && tcpPacket.getACK() != -1) { // is ACK and is not corrupted
                        if (base > tcpPacket.getACK()) { // duplicate ACK
                            s.acquire();
                            setTimer(false); // stop timer
                            nextSeqNum = base; // reset nextSeqNum
                            s.release();
                        } else if (tcpPacket.isFIN()){ // teardown signal
                            transferComplete = true; // we done here
                            setTimer(false);
                            System.out.println("TCPSender: InThread: Teardown ACK received");
                        } else { // normal ACK
                            base = tcpPacket.getACK() + 1;  // update base number
                            s.acquire();
                            if (base == nextSeqNum) setTimer(false); // if there are no unacknowledge packets then stop timer
                            else setTimer(true); // otherwise wait for next packet
                            s.release();
                        }
                    }

                    

                }
            } catch (InterruptedException e) {
                System.err.println("TCPSender: InThread: Exception while claiming semaphore. Attempting to carry on.");
                e.printStackTrace();
            } catch (IOException e) {
                System.err.println("TCPSender: InThread: Exception during communication. Attempting to carry on.");
                e.printStackTrace();
            } finally {
               socket.close();
           }
        }
    }
    
    public class Timeout extends TimerTask {
        public void run() {
            System.out.println("TCPSender: Timeout: Go-Back-N Triggered, going back to packet " + base);
            goBackN();
        }
    }
    
    private void goBackN() {
        try {
            s.acquire();
            nextSeqNum = base; // do the go-back-n
            s.release();
        } catch (InterruptedException e) {
            System.err.println("TCPSender: Exception while claiming semaphore. Attempting to carry on.");
            e.printStackTrace();
        }
    }
    
    public TCPSender(int recvPort, String path, String filename) {
        System.out.println("TCPSender: Starting Up TCPSender");
      
        this.path = path; //((path.substring(path.length()-1)).equals("/")) ? path : path + "/"; // properly format path;
        this.filename = filename;
        packetList = new Vector<byte[]>(winSize);
        transferComplete = false;
        DatagramSocket inSocket;
        s = new Semaphore(1);
        
        try {
            // create the socket
            inSocket = new DatagramSocket(recvPort); // bind to port recvPort
            
            // create the threads to process data
            InThread inThread = new InThread(inSocket); // receive ACKs through inSocket
            inThread.start();
        } catch (SocketException e) {
            System.err.println("TCPSender: Exception while creating inSocket");
            e.printStackTrace();
            System.exit(-1);
        }
    }
    
    public static void main(String[] args) {
        new TCPSender(14416,"","hello_repeat.txt"); // dstPort, recvPort, path, filename
    }

}
