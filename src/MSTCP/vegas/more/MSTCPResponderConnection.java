package MSTCP.vegas.more;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.math.BigInteger;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.Timer;
import java.util.TimerTask;
import java.util.logging.Level;
import java.util.logging.Logger;

public class MSTCPResponderConnection {
    final Logger logger;

    MSTCPResponder responder;

    int initialSeqNum;
    int nextSeqNum;

    MSTCPInformation mstcpInfo;

    int dstPort;
    InetAddress dstAddress;
    int cwnd;
    LinkedList<Integer> toAck = new LinkedList<Integer>();

    // boolean connected = false;
    boolean sentFinAck = false;
    RandomAccessFile raf;

    Timer timer;

    private void stopTimer() {
        if (timer != null)
            timer.cancel();
    }

    private void setTimer() {
        stopTimer();
        timer = new Timer();
        timer.schedule(new FINACKTimeout(), Utils.finTimeout);
    }

    private class FINACKTimeout extends TimerTask {
        public void run() {
            logger.info("Timeout after sending FIN + ACK. Closing Connection to (" + dstAddress + ", " + dstPort + ")");
            TCPPacket fin = new TCPPacket(responder.recvPort, dstPort, nextSeqNum, cwnd);
            fin.setFIN();
            fin.setACK(nextSeqNum);
            byte[] finBytes = fin.bytes();
            try {
                responder.socket.send(
                        new DatagramPacket(finBytes, finBytes.length, InetAddress.getByName(responder.srcInfo.address),
                                Utils.router ? Utils.router_port : responder.srcInfo.port));
            } catch (IOException e) {
                logger.log(Level.SEVERE, e.getMessage(), e);
                System.exit(1);
            }
        }
    }

    public byte[] generateTCPPacket(int seqNum, byte[] dataBytes, boolean syn, boolean fin, int time_req) {
        TCPPacket tcpPacket = new TCPPacket(responder.recvPort, dstPort, seqNum, cwnd, dataBytes);
        if (syn)
            tcpPacket.setSYN();
        if (fin)
            tcpPacket.setFIN();

        tcpPacket.setACK(toAck.isEmpty() ? nextSeqNum : toAck.removeFirst());

        tcpPacket.setTime_req(time_req);
        tcpPacket.setTime_ack();
        return tcpPacket.bytes();
    }

    public MSTCPResponderConnection(TCPPacket syn, MSTCPResponder responder, int time_req) {
        this.logger = responder.logger; // TODO: its own logger or prefix logging messages?

        try {

            this.responder = responder;
            this.initialSeqNum = syn.getSeqNum();
            this.nextSeqNum = this.initialSeqNum;
            this.mstcpInfo = new MSTCPInformation(syn.getData());
            this.mstcpInfo.sources = responder.sources;
            this.dstAddress = InetAddress.getByAddress(mstcpInfo.recvAddr);
            this.dstPort = mstcpInfo.recvPort;

            for (SourceInformation s : mstcpInfo.sources) { // set this sender to connected
                if (s.address == responder.srcInfo.address && s.port == responder.srcInfo.port) {
                    s.connected = true;
                }
            }

            this.raf = new RandomAccessFile(new File(responder.path + mstcpInfo.filename), "r");
            this.mstcpInfo.fileSize = this.raf.length();

            logger.info("Opened File " + mstcpInfo.filename + ". Sending SYN + ACK to (" + dstAddress + ", " + dstPort + ")");
            byte[] outBytes = generateTCPPacket(initialSeqNum, mstcpInfo.bytes(), true, false, time_req);
            responder.socket.send(new DatagramPacket(outBytes, outBytes.length, dstAddress,
                    Utils.router ? Utils.router_port : dstPort));

            DatagramPacket udpPkt;
            TCPPacket inPacket;
            int time_recv;

            long remaining = -1;
            byte[] dataBytes = new byte[Utils.transferSize];
            BigInteger data;

            for (;;) {
                udpPkt = responder.socket.receive();

                time_recv = (int) (System.currentTimeMillis() % Integer.MAX_VALUE);
                inPacket = new TCPPacket(udpPkt.getData());

                if (inPacket.verifyChecksum()) {
                    if (inPacket.isSYN()) {
                        logger.info("Sending SYN + ACK to (" + dstAddress + ", " + dstPort + ")");
                        outBytes = generateTCPPacket(initialSeqNum, mstcpInfo.bytes(), true, false, time_req);
                    } else {
                        time_req = time_recv - inPacket.getTime_req();
                        if (time_req < 0)
                            time_req = (Integer.MAX_VALUE - inPacket.getTime_req()) + time_recv;
    
                        if (inPacket.isFIN() && !sentFinAck) {
                            logger.info("Received FIN packet " + inPacket.getSeqNum() + ". Sending FIN + ACK to (" + dstAddress + ", " + dstPort + ")");
                            MOREPacket more = new MOREPacket(inPacket.getData());
                            more.setPacketType(MOREPacket.RETURN_PACKET);
                            outBytes = generateTCPPacket(inPacket.getSeqNum(), more.bytes(), false, true, time_req);
                            sentFinAck = true;
                            setTimer();
                        } else if (inPacket.isACK() && sentFinAck && inPacket.getSeqNum() >= nextSeqNum) {
                            logger.info("Received ACK after sending FIN + ACK. Closing Connection to (" + dstAddress + ", " + dstPort + ")");
                            stopTimer();
                            break;
                        } else { // normal request packet
                            if (inPacket.getSeqNum() == nextSeqNum)
                                nextSeqNum++;
                            else if (inPacket.getSeqNum() > nextSeqNum) {
                                toAck.add(inPacket.getSeqNum());
                                nextSeqNum = inPacket.getSeqNum() + 1;
                            }
    
                            logger.info("Received packet " + inPacket.getSeqNum());
    
                            MOREPacket more = new MOREPacket(inPacket.getData());
                            CodeVectorElement[] codeVector = more.getCodeVector();
                            BigInteger encodedData = BigInteger.ZERO;
                            for (CodeVectorElement c : codeVector) {
                                if (c.getBlock() == -1 || c.getCoefficient() == 0)
                                    continue;
                                logger.info("Block " + c.getBlock() + " with coefficient " + c.getCoefficient());
                                raf.seek(c.getBlock() * Utils.blockSize);
                                remaining = raf.length() - c.getBlock() * Utils.blockSize;
                                if (remaining < 0) // case for when the file is less than 1 block in size
                                    remaining = raf.length();
                                if (remaining < Utils.blockSize)
                                    dataBytes = new byte[(int) remaining + 1];
                                else if (dataBytes.length < Utils.transferSize)
                                    dataBytes = new byte[Utils.transferSize];
                                dataBytes[0] = 1;
                                raf.read(dataBytes, 1, dataBytes.length - 1);
                                data = new BigInteger(dataBytes); // TODO: better variable names
                                data = data.multiply(BigInteger.valueOf(c.getCoefficient()));
                                encodedData = encodedData.add(data);
                            }
    
                            more.setPacketType(MOREPacket.RETURN_PACKET);
                            more.setEncodedData(encodedData);
                            outBytes = generateTCPPacket(inPacket.getSeqNum(), more.bytes(), false, false, time_req);
                            logger.info("Sending ACK + Encoded Data to (" + dstAddress + ", " + dstPort + ")");
                            Arrays.fill(dataBytes, (byte) 0);
                        }
                    }
                    responder.socket.send(new DatagramPacket(outBytes, outBytes.length, dstAddress, Utils.router ? Utils.router_port : dstPort));
                } else {
                    logger.info("Received Corrupted Packet");
                }

            }

        } catch (IOException e) {
            logger.log(Level.SEVERE, e.getMessage(), e);
            System.exit(1);
        } finally {
            try {
                if (raf != null)
                    raf.close();
            } catch (IOException e) {
                logger.log(Level.SEVERE, e.getMessage(), e);
                System.exit(1);
            }
            logger.info("Completed connection.");
        }
    }
}
