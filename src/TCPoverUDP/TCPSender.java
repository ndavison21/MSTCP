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
import java.util.zip.CRC32;

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
//        if (timer != null) timer.cancel(); // stops the current timer
//        if (newTimer) {
//            timer = new Timer(); // start a new one if necessary
//            timer.schedule(new Timeout(), timeoutVal);
//        }
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
        
        // attaches a header to the data bytes
        public byte[] generatePacket(int seqNum, byte[] dataBytes) {
            byte[] seqNumBytes = ByteBuffer.allocate(4).putInt(seqNum).array();
            
            // generates the checksum
            CRC32 checksum = new CRC32();
            checksum.update(seqNumBytes);
            checksum.update(dataBytes);
            byte[] checksumBytes = ByteBuffer.allocate(8).putLong(checksum.getValue()).array();
            
            // putting together the packet
            ByteBuffer packetBuffer = ByteBuffer.allocate(8 + 4 + dataBytes.length);
            packetBuffer.put(checksumBytes);
            packetBuffer.put(seqNumBytes);
            packetBuffer.put(dataBytes);
            return packetBuffer.array();
        }
        
        // attaches a TCP header to the data bytes
        public byte[] generateTCPPacket(int seqNum, byte[] dataBytes, boolean finalSeqNum) {
            TCPPacket tcpPacket = new TCPPacket(this.recvPort, this.dstPort, seqNum, TCPSender.winSize);
            if (finalSeqNum) tcpPacket.setFIN();
            byte[] tcpBytes = tcpPacket.bytes();
            
            ByteBuffer packetBuffer = ByteBuffer.allocate(tcpBytes.length + dataBytes.length);
            packetBuffer.put(tcpBytes);
            packetBuffer.put(dataBytes);
            return packetBuffer.array();
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
                            if (base == nextSeqNum) setTimer(true); // if sending the first packet of the window, start timer
                            byte[] outData = new byte[10];
                            boolean finalSeqNum = false;
                            
                            if (nextSeqNum < packetList.size()) { // if packet has been constructed before (happens if we have gone-back-n)
                                outData = packetList.get(nextSeqNum);
                            } else {
                                if (nextSeqNum == 0) { // if it's the first packet then include the file information
                                    byte[] filenameBytes = filename.getBytes();
                                    byte[] filenameLengthBytes = ByteBuffer.allocate(4).putInt(filenameBytes.length).array();
                                    byte[] dataBuffer = new byte[dataSize];
                                    int dataLength = fis.read(dataBuffer, 0, dataSize - 4 - filenameBytes.length);
                                    byte[] dataBytes = Arrays.copyOfRange(dataBuffer, 0, dataLength);
                                    ByteBuffer bb = ByteBuffer.allocate(4 + filenameBytes.length + dataBytes.length);
                                    bb.put(filenameLengthBytes);
                                    bb.put(filenameBytes);
                                    bb.put(dataBytes);
                                    if (TCPPacket.useTCP)
                                        outData = generateTCPPacket(nextSeqNum, bb.array(), finalSeqNum);
                                    else
                                        outData = generatePacket(nextSeqNum, bb.array());
                                } else { // for subsequent packets just send data
                                    byte[] dataBuffer = new byte[dataSize];
                                    int dataLength = fis.read(dataBuffer, 0, dataSize);
                                    if (dataLength == -1) { // if no more data then send nothing (finalSeqNum)
                                        finalSeqNum = true;
                                        if (TCPPacket.useTCP) {
                                            outData = generateTCPPacket(nextSeqNum, new byte[0], finalSeqNum);
                                        } else
                                            outData = generatePacket(nextSeqNum, new byte[0]);
                                    } else { // otherwise send the valid data
                                        byte[] dataBytes = Arrays.copyOfRange(dataBuffer, 0, dataLength);
                                        if (TCPPacket.useTCP)
                                            outData = generateTCPPacket(nextSeqNum, dataBytes, finalSeqNum);
                                        else
                                            outData = generatePacket(nextSeqNum, dataBytes);
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
        
        public InThread(DatagramSocket socket) {
            this.socket = socket;
        }
        
        // returns -1 if corrupted, otherwise just returns ACK number
        int decodePacket(byte[] packetBytes) {
            byte[] receivedChecksumBytes = Arrays.copyOfRange(packetBytes, 0, 8);
            byte[] ackNumBytes = Arrays.copyOfRange(packetBytes, 8, 12);
            CRC32 checksum = new CRC32();
            checksum.update(ackNumBytes);
            byte[] calculatedChecksumBytes = ByteBuffer.allocate(8).putLong(checksum.getValue()).array();
            if (Arrays.equals(receivedChecksumBytes, calculatedChecksumBytes)) return ByteBuffer.wrap(ackNumBytes).getInt();
            else return -1;
        }
        
        
        // process to receive ACKs, updating base of window as it goes along
        public void run() {
            System.out.println("TCPSender: InThread: Started");
            byte[] ackBytes = new byte[TCPPacket.useTCP ? 20 : 12];
            DatagramPacket ack = new DatagramPacket(ackBytes, ackBytes.length);
            
            try {
                System.out.println("TCPSender: InThread: Beginning waiting for ACKs");
                while (!transferComplete) { // while there are still packets to be ACKed
                    socket.receive(ack);
                    
                    if (TCPPacket.useTCP) {
                        TCPPacket tcpPacket = new TCPPacket(ackBytes);
                        if (tcpPacket.isACK() && tcpPacket.getACK() != -1) { // is ACK and is not corrupted
                            if (base == tcpPacket.getACK() + 1) { // duplicate ACK
                                s.acquire();
                                setTimer(false); // stop timer
                                nextSeqNum = base; // reset nextSeqNum
                                s.release();
                            } else if (tcpPacket.isFIN()){ // teardown signal
                                transferComplete = true; // we done here
                                System.out.println("TCPSender: InThread: Teardown ACK received");
                            } else { // normal ACK
                                base = tcpPacket.getACK() + 1;  // update base number
                                s.acquire();
                                if (base == nextSeqNum) setTimer(false); // if there are no unacknowledge packets then stop timer
                                else setTimer(true); // otherwise wait for next packet
                                s.release();
                            }
                        }
                    } else {
                        int ackNum = decodePacket(ackBytes);
                        if (ackNum != -1) { // if ACK is not corrupted
                            if (base == ackNum + 1) { // duplicate ACK
                                s.acquire();
                                setTimer(false); // stop timer
                                nextSeqNum = base; // reset nextSeqNum
                                s.release();
                            } else if(ackNum == -2) { // teardown signal
                                transferComplete = true; // we done here
                                System.out.println("TCPSender: InThread: Teardown ACK received");
                            } else { // normal ACK
                                base = ackNum++;  // update base number
                                s.acquire();
                                if (base == nextSeqNum) setTimer(false); // if there are no unacknowledge packets then stop timer
                                else setTimer(true); // otherwise wait for next packet
                                s.release();
                            }
                        }
                        // if ACK corrupted then nothing we can do
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
            System.out.println("TCPSender: Timeout: Go-Back-N Triggered");
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
    
    public TCPSender(int dstPort, int recvPort, String path, String filename) {
        System.out.println("TCPSender: Starting Up TCPSender");
        
        base = 0;
        nextSeqNum = 0;
        this.path = path; //((path.substring(path.length()-1)).equals("/")) ? path : path + "/"; // properly format path;
        this.filename = filename;
        packetList = new Vector<byte[]>(winSize);
        transferComplete = false;
        DatagramSocket outSocket, inSocket;
        s = new Semaphore(1);
        
        try {
            // create the sockets
            outSocket =  new DatagramSocket();      // bind to any available port
            inSocket = new DatagramSocket(recvPort); // bind to port inSocketPort
            
            // create the threads to process data
            InThread inThread = new InThread(inSocket); // receive ACKs through inSocket
            OutThread outThread = new OutThread(outSocket, dstPort, recvPort);
            inThread.start();
            outThread.start();
        } catch (SocketException e) {
            System.err.println("TCPSender: Exception while creating sockets");
            e.printStackTrace();
            System.exit(-1);
        }
    }
    
    public static void main(String[] args) {
        new TCPSender(14415,14416,"","hello_repeat.txt"); // dstPort, recvPort, path, filename
    }

}
