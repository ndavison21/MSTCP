package MSTCP.vegas.more;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.math.BigInteger;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.util.Arrays;
import java.util.Collections;
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
                        new DatagramPacket(finBytes, finBytes.length, InetAddress.getByName(responder.recvAddr), responder.routerPort));
            } catch (IOException e) {
                logger.log(Level.SEVERE, e.getMessage(), e);
                e.printStackTrace();
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
        
        int ack = syn ? initialSeqNum : toAck.isEmpty() ? nextSeqNum : Collections.min(toAck);
        tcpPacket.setACK(ack);

        tcpPacket.setTime_req(time_req);
        tcpPacket.setTime_ack();
        return tcpPacket.bytes();
    }

    public MSTCPResponderConnection(TCPPacket syn, MSTCPResponder responder, int time_req, int recvPort) {
        this.logger = responder.logger;

        try {

            this.responder = responder;
            this.initialSeqNum = syn.getSeqNum();
            this.nextSeqNum = this.initialSeqNum + 1;
            this.mstcpInfo = new MSTCPInformation(syn.getData());
            this.mstcpInfo.sources = responder.sources;
            this.dstAddress = InetAddress.getByAddress(mstcpInfo.recvAddr);
            this.dstPort = mstcpInfo.recvPort;

            for (SourceInformation s : mstcpInfo.sources) { // set this sender to connected
                if (s.address == responder.recvAddr) {
                    s.ports.put(recvPort, true);
                    s.tried.put(recvPort, true);
                    s.connected++;
                }
            }

            this.raf = new RandomAccessFile(new File(responder.path + mstcpInfo.filename), "r");
            this.mstcpInfo.fileSize = this.raf.length();

            logger.info("Opened File " + mstcpInfo.filename + ". Sending SYN + ACK to (" + dstAddress + ", " + dstPort + ")");
            byte[] outBytes = generateTCPPacket(initialSeqNum, mstcpInfo.bytes(), true, false, time_req);
            responder.socket.send(new DatagramPacket(outBytes, outBytes.length, dstAddress, responder.routerPort));

            DatagramPacket udpPkt;
            TCPPacket inPacket;
            int time_recv;

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

                        if (sentFinAck) {
                            if (inPacket.isACK() && inPacket.getSeqNum() >= nextSeqNum) {
                                logger.info("Received ACK after sending FIN + ACK. Closing Connection to (" + dstAddress + ", " + dstPort + ")");
                                stopTimer();
                                break;
                            } else {
                                logger.info("Received packet while waiting for ACK after sending FIN + ACK.");
                                continue;
                            }
                        } else {
                            if (inPacket.isFIN()) {
                                logger.info("Received FIN packet " + inPacket.getSeqNum() + ". Sending FIN + ACK to (" + dstAddress + ", " + dstPort + ")");
                                MOREPacket more = new MOREPacket(inPacket.getData());
                                more.setPacketType(MOREPacket.RETURN_PACKET);
                                outBytes = generateTCPPacket(inPacket.getSeqNum(), more.bytes(), false, true, time_req);
                                sentFinAck = true;
                                nextSeqNum = inPacket.getSeqNum();
                                setTimer();
                            } else { // normal request packet
                                if (inPacket.getSeqNum() > nextSeqNum) {
                                    for (int a=nextSeqNum; a<inPacket.getSeqNum(); a++)
                                        toAck.add(a);
                                } else if (toAck.contains(inPacket.getSeqNum())) {
                                    toAck.remove((Integer) inPacket.getSeqNum());
                                }
                                nextSeqNum = Math.max(nextSeqNum, inPacket.getSeqNum() + 1);
        
                                logger.info("Received packet " + inPacket.getSeqNum());
        
                                MOREPacket more = new MOREPacket(inPacket.getData());
                                CodeVectorElement[] codeVector = more.getCodeVector();
                                BigInteger encodedData = BigInteger.ZERO;

                                if (Utils.decode) {
                                    for (CodeVectorElement c : codeVector) {
                                        if (c.getBlock() == -1 || c.getCoefficient() == 0)
                                            continue;
    //                                    logger.info("Block " + c.getBlock() + " with coefficient " + c.getCoefficient());
                                        raf.seek(c.getBlock() * Utils.blockSize);
                                        if ((c.getBlock() + 1L) * Utils.blockSize > raf.length())
                                            dataBytes = new byte[(int) (raf.length() - (c.getBlock() * Utils.blockSize)) + 1];
                                        else if (dataBytes.length != Utils.transferSize)
                                            dataBytes = new byte[Utils.transferSize];
                                        dataBytes[0] = 1;
                                        raf.read(dataBytes, 1, dataBytes.length - 1);
                                        data = new BigInteger(dataBytes);       
                                        data = data.multiply(BigInteger.valueOf(c.getCoefficient()));
                                        encodedData = encodedData.add(data);
                                    }
                                    more.setEncodedData(encodedData);
                                } else {
                                    more.setEncodedData(new byte[800]);
                                }
        
                                more.setPacketType(MOREPacket.RETURN_PACKET);
                                outBytes = generateTCPPacket(inPacket.getSeqNum(), more.bytes(), false, false, time_req);
                                logger.info("Sending ACK " + (toAck.isEmpty() ? nextSeqNum : Collections.min(toAck)) + " + Encoded Data to (" + dstAddress + ", " + dstPort + ")");
                                Arrays.fill(dataBytes, (byte) 0);
                            }
                            
                        }
                    }
                    responder.socket.send(new DatagramPacket(outBytes, outBytes.length, dstAddress, responder.routerPort));
                } else {
                    logger.info("Received Corrupted Packet");
                }

            }
            
            for (SourceInformation s : mstcpInfo.sources) { // set this sender to connected
                if (s.address == responder.recvAddr) {
                    s.ports.put(recvPort, false);
                    s.connected--;
                }
            }

        } catch (IOException e) {
            logger.log(Level.SEVERE, e.getMessage(), e);
            e.printStackTrace();
            System.exit(1);
        } finally {
            try {
                if (raf != null)
                    raf.close();
            } catch (IOException e) {
                logger.log(Level.SEVERE, e.getMessage(), e);
                e.printStackTrace();
                System.exit(1);
            }
            logger.info("Completed connection.");
        }
    }
}
