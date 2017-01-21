package TCPoverUDP;

import java.nio.ByteBuffer;
import java.util.Arrays;

public class TCPPacket {
    
    private int srcPort    = 0;    // 16 bits, port at sender
    private int destPort   = 0;    // 16 bits, port at receiver
    private int seqNum     = 0;    // 32 bits, if SYN flag set then the initial sequence number, otherwise the accumulated sequence number
    private int ackNum     = 0;    // 32 bits, if ACK flag set then next expected sequence number, otherwise empty
    private int dataOffset = 0;    //  4 bits, size of header in 32-bit words
    private int flags      = 0;    //  9 bits, contains 9 1-bit flags (see reference)
    private int windowSize = 0;    // 16 bits, size of the receive window
    private int checksum   = 0;    // 16 bits, 16-bit checksum
    private int urgentPointer = 0; // 16 bits, if URG flag set then offset to last urgent data byte
    private byte[] options = null; // 0-320 bits divisible by 32, final byte includes any padding
    
    private int paddingLength = 0;
    
    
    public TCPPacket(int srcPort, int destPort, int seqNum, int ackNum, int flags, int windowSize,
            int urgentPointer, byte[] options) {
        this.srcPort = srcPort;
        this.destPort = destPort;
        this.seqNum = seqNum;
        this.ackNum = ackNum;
        this.flags = flags;
        this.windowSize = windowSize;
        this.urgentPointer = urgentPointer;
        this.options = options;
        
        int optionsLength = (options == null ? 0 : options.length) * 8;

        paddingLength = (160 + optionsLength) % 32;
        dataOffset = (160 + optionsLength + paddingLength) / 32;
    }
    
   
    public TCPPacket(int srcPort, int destPort, int seqNum, int windowSize) {
        super();
        this.srcPort = srcPort;
        this.destPort = destPort;
        this.seqNum = seqNum;
        this.windowSize = windowSize;
        paddingLength = 160 % 32;
        dataOffset = (160 + paddingLength) / 32;
    }

    public int getSrcPort() {
        return srcPort;
    }

    public void setSrcPort(int srcPort) {
        this.srcPort = srcPort;
    }

    public int getDestPort() {
        return destPort;
    }

    public void setDestPort(int destPort) {
        this.destPort = destPort;
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
            
            this.dataOffset = 5 + (this.options.length / 4);
                
        }
    }

    public int getDataOffset() {
        return dataOffset;
    }
    
    public TCPPacket(byte[] packetBytes) {
        this.srcPort = ByteBuffer.wrap(packetBytes, 0, 2).getShort();
        this.destPort = ByteBuffer.wrap(packetBytes, 2, 2).getShort();
        this.seqNum = ByteBuffer.wrap(packetBytes, 4, 4).getInt();
        this.ackNum = ByteBuffer.wrap(packetBytes, 8, 4).getInt();
        
        this.dataOffset = packetBytes[12] >> 4;
        this.flags = ((packetBytes[12] & 1) << 8) + packetBytes[13];
        this.windowSize = ByteBuffer.wrap(packetBytes, 14, 2).getShort();
        this.checksum = ByteBuffer.wrap(packetBytes, 16, 2).getShort();
        this.urgentPointer = ByteBuffer.wrap(packetBytes, 18, 2).getShort();
        
        if (dataOffset > 5) this.options = Arrays.copyOfRange(packetBytes, 20, packetBytes.length);
                
    }
    
    private ByteBuffer constructByteBuffer() {
        ByteBuffer bb = ByteBuffer.allocate(dataOffset * 4);
        
        bb.putShort((short) srcPort);
        bb.putShort((short) destPort);
        bb.putInt(seqNum);
        bb.putInt(ackNum);
        
        int temp = (dataOffset << 4) + (flags >> 8);
        bb.put((byte) temp);
        bb.put((byte) flags);
        
        bb.putShort((short) windowSize);
        bb.putShort((short) 0); // checksum done below
        bb.putShort((short) urgentPointer);
        if (options != null && options.length > 0) bb.put(options);
        
        return bb;
    }
     
    public byte[] bytes() {
        byte[] packetBytes = constructByteBuffer().array();

        short sum = calculateChecksum();
        packetBytes[16] = (byte) (sum >> 8);
        packetBytes[17] = (byte) (sum);

        return packetBytes;
    }
    
    public short calculateChecksum() {
        byte[] packetBytes = constructByteBuffer().array();
        
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
        TCPPacket pkt1 = new TCPPacket(0, 0, 0, 0, 0, 0, 0, null);
        TCPPacket pkt2 = new TCPPacket(pkt1.bytes());
        
        byte[] pkt1bytes = pkt1.bytes();
        byte[] pkt2bytes = pkt2.bytes();
        System.out.println(Arrays.equals(pkt1bytes, pkt2bytes));
    }
    
}
