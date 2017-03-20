package MSTCP.vegas;

import java.nio.ByteBuffer;
import java.util.Arrays;

public class TCPPacket {
    static int BASE_SIZE = 224; // base size of the TCP packet in bytes (7 32-bit words)
    
    private int srcPort    = 0;    // 16 bits, port at sender
    private int dstPort    = 0;    // 16 bits, port at receiver
    private int seqNum     = 0;    // 32 bits, if SYN flag set then the initial sequence number, otherwise the accumulated sequence number
    private int ackNum     = 0;    // 32 bits, if ACK flag set then next expected sequence number, otherwise empty
    private int dataOffset = 0;    //  4 bits, size of header in 32-bit words
    private int flags      = 0;    //  9 bits, contains 9 1-bit flags (see reference)
    private int windowSize = 0;    // 16 bits, size of the receive window
    private int checksum   = 0;    // 16 bits, 16-bit checksum
    private int urgentPointer = 0; // 16 bits, if URG flag set then offset to last urgent data byte
    private byte[] options = null; // 0-320 bits divisible by 32, final byte includes any padding
    private byte[] data = null;    // (0-840 bytes) data the TCP is a header of
    
    private int time_req = -1; // 32 bits, in a request records the time the packet was sent. In an ack records the latency of the request.
    private int time_ack = -1; // 32 bits, in a request is empty. In an ack Records the time the packet was sent.
    
    
    private int paddingLength = 0;
    
    
    public TCPPacket(int srcPort, int dstPort, int seqNum, int ackNum, int flags, int windowSize,
            int urgentPointer, byte[] options) {
        this (srcPort, dstPort, seqNum, windowSize);
        this.ackNum = ackNum;
        this.flags = flags;
        this.urgentPointer = urgentPointer;
        this.options = options;
        
        int optionsLength = (options == null ? 0 : options.length) * 8;

        paddingLength = (BASE_SIZE + optionsLength) % 32;
        dataOffset = (BASE_SIZE + optionsLength + paddingLength) / 32;
    }
    
   
    public TCPPacket(int srcPort, int dstPort, int seqNum, int windowSize) {
        this.srcPort = srcPort;
        this.dstPort = dstPort;
        this.seqNum = seqNum;
        this.windowSize = windowSize;
        paddingLength = BASE_SIZE % 32;
        dataOffset = (BASE_SIZE + paddingLength) / 32;
    }
    
    public TCPPacket(int srcPort, int dstPort, int seqNum, int ackNum, int flags, int windowSize,
            int urgentPointer, byte[] options, byte[] data) {
        this(srcPort, dstPort, seqNum, ackNum, flags, windowSize, urgentPointer, options);
        this.data = data;
    }
    
    public TCPPacket(int srcPort, int dstPort, int seqNum, int windowSize, byte[] data) {
        this(srcPort, dstPort, seqNum, windowSize);
        this.data = data;
    }
    
    // WARNING: this should only be used for checking if a packet is in a collection
    // public TCPPacket(int seqNum) {
    //     this.seqNum = seqNum;
    // }
    
    @Override
    public boolean equals(Object o) {
        if (!(o instanceof TCPPacket))
            return false;
        
        TCPPacket tcpPkt = (TCPPacket) o;
        return this.seqNum == tcpPkt.seqNum;
    }

    public int getSrcPort() {
        return srcPort;
    }

    public void setSrcPort(int srcPort) {
        this.srcPort = srcPort;
    }

    public int getDestPort() {
        return dstPort;
    }

    public void setDestPort(int dstPort) {
        this.dstPort = dstPort;
    }

    public int getSeqNum() {
        return seqNum;
    }

    public void setSeqNum(int seqNum) {
        this.seqNum = seqNum;
    }

    public boolean isACK() {
        return (this.flags & 8) > 0;
    }    

    public int getACK() {
        return ackNum;
    }

    public void setACK(int ackNum) {
        this.ackNum = ackNum;
        flags |= 8;
    }
    
    public boolean isRST() {
        return (this.flags & 4) > 0;
    }
    
    public void setRST() {
        flags |= 4;
    }

    public boolean isSYN() {
        return (this.flags & 2) > 0;
    }
    
    public void setSYN() {
        flags |= 2;
    }
    
    public boolean isFIN() {
        return (this.flags & 1) > 0;
    }
    
    public void setFIN() {
        flags |= 1;
    }

    public int getFlags() {
        return flags;
    }

    public void setFlags(int flags) {
        this.flags = flags;
    }
    
    public int getTime_req() {
        return this.time_req;
    }
    
    public void setTime_req() {
        this.time_req = (int) (System.currentTimeMillis() % Integer.MAX_VALUE);
    }
    
    public void setTime_req(int time_req) {
        this.time_req = time_req;
    }
    
    public int getTime_ack() {
        return this.time_ack;
    }
    
    public void setTime_ack() {
        this.time_ack = (int) (System.currentTimeMillis() % Integer.MAX_VALUE);
    }

    public int getWindowSize() {
        return windowSize;
    }

    public void setWindowSize(int windowSize) {
        this.windowSize = windowSize;
    }

    public byte[] getOptions() {
        return options;
    }

    public void setOptions(byte[] options) {
        if (options != null) {
            if ((options.length % 4) > 0) {
                this.options = new byte[options.length + (options.length % 4)];
                for (int i=0; i<options.length; i++) {
                    this.options[i] = options[i];
                }
            } else {
                this.options = options;
            }
            
            this.dataOffset = (BASE_SIZE / 32) + (this.options.length / 4);
                
        }
    }

    public int getDataOffset() {
        return dataOffset;
    }
    
    public void setData(byte[] data) {
        this.data = data;
    }
    
    public byte[] getData() {
        return this.data;
    }
    
    
    public TCPPacket(byte[] packetBytes) {
        this.srcPort = ByteBuffer.wrap(packetBytes, 0, 2).getShort();
        this.dstPort = ByteBuffer.wrap(packetBytes, 2, 2).getShort();
        this.seqNum = ByteBuffer.wrap(packetBytes, 4, 4).getInt();
        this.ackNum = ByteBuffer.wrap(packetBytes, 8, 4).getInt();
        
        this.dataOffset = packetBytes[12] >> 4;
        this.flags = ((packetBytes[12] & 1) << 8) + packetBytes[13];
        this.windowSize = ByteBuffer.wrap(packetBytes, 14, 2).getShort();
        this.checksum = ByteBuffer.wrap(packetBytes, 16, 2).getShort();
        this.urgentPointer = ByteBuffer.wrap(packetBytes, 18, 2).getShort();
        
        this.time_req = ByteBuffer.wrap(packetBytes, 20, 4).getInt();
        this.time_ack = ByteBuffer.wrap(packetBytes, 24, 4).getInt();
        
        if (dataOffset > (BASE_SIZE / 32))
            this.options = Arrays.copyOfRange(packetBytes, 20, packetBytes.length);
        if (dataOffset * 4 < packetBytes.length)
            this.data = Arrays.copyOfRange(packetBytes, dataOffset * 4, packetBytes.length);
                
    }
    
    
    
    private ByteBuffer constructByteBuffer() {
        ByteBuffer bb = ByteBuffer.allocate( (dataOffset * 4) + (data == null ? 0 : data.length));
        
        bb.putShort((short) srcPort);
        bb.putShort((short) dstPort);
        bb.putInt(seqNum);
        bb.putInt(ackNum);
        
        int temp = (dataOffset << 4) + (flags >> 8);
        bb.put((byte) temp);
        bb.put((byte) flags);
        
        bb.putShort((short) windowSize);
        bb.putShort((short) 0); // checksum done below
        bb.putShort((short) urgentPointer);
        
        bb.putInt(time_req);
        bb.putInt(time_ack);
        
        if (options != null && options.length > 0) 
            bb.put(options);
        if (data != null)
            bb.put(data);
        
        return bb;
    }
     
    public byte[] bytes() {
        byte[] packetBytes = constructByteBuffer().array();

        short sum = calculateChecksum(packetBytes);
        packetBytes[16] = (byte) (sum >> 8);
        packetBytes[17] = (byte) (sum);
        

        return packetBytes;
    }
    
    public short calculateChecksum() {
        return calculateChecksum(constructByteBuffer().array());
    }
    
    public short calculateChecksum(byte[] packetBytes) {        
        short sum = 0;
        for (int i=0; i<packetBytes.length; i++) {
            sum += packetBytes[i];
        }
        return sum;
    }
   
    public boolean verifyChecksum() {
        return this.checksum == calculateChecksum();
    }
    
    public static void main(String[] args) {
        byte[] data = {0, 1, 2, 3, -1, -2, -3, -4};
        TCPPacket pkt1 = new TCPPacket(14000, 15000, 50, 10, data);
        pkt1.setACK(54);
        pkt1.setTime_ack();
        pkt1.setTime_req();
        byte[] pkt1Bytes = pkt1.bytes();
        TCPPacket pkt2 = new TCPPacket(pkt1Bytes);
        boolean result = pkt2.verifyChecksum();
        System.out.println(result);
    }
}
