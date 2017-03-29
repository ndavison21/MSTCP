package MSTCP.vegas.more;

import java.nio.ByteBuffer;

public class MOREPacket {
    static int BASE_SIZE = Utils.moreSize * 8; // base size of the packet in bits without code vector or encoded data
    

    private int   flowID     = -1;  // 32 bits, flow identifier, hopefully unique
    private short packetType = -1;  // 16 bits, (0 means forward packet, 1 means response packet)

    private short vectorLength;    // 16 bits, number of elements in the codeVector
    private short[] codeVector;    // array of 16 bit shorts, blocks and coefficients of encoded data.
    private byte[] encodedData;    // ? bits, combined packets
    
    public MOREPacket(int flowID, short packetType, short[] codeVector, byte[] encodedData) {
        super();
        this.flowID = flowID;
        this.packetType = packetType;
        this.vectorLength = (short) (codeVector == null ? 0 : codeVector.length);
        this.codeVector = codeVector;
        this.encodedData = encodedData;
    }
    
    public MOREPacket(int flowID, short[] codeVector) {
        this(flowID, (short) 0, codeVector, null);
    }

    public int getFlowID() {
        return flowID;
    }

    public void setFlowID(int flowID) {
        this.flowID = flowID;
    }

    public short getPacketType() {
        return packetType;
    }

    public void setPacketType(short packetType) {
        this.packetType = packetType;
    }
    
    public short getVectorLength() {
        return vectorLength;
    }

    public short[] getCodeVector() {
        return codeVector;
    }

    public void setCodeVector(short[] codeVector) {
        this.vectorLength = (short) (codeVector == null ? 0 : codeVector.length);
        this.codeVector = codeVector;
    }

    public byte[] getEncodedData() {
        return encodedData;
    }

    public void setEncodedData(byte[] encodedData) {
        this.encodedData = encodedData;
    }
    
    
    public MOREPacket(byte[] packetBytes) {
        ByteBuffer bb = ByteBuffer.wrap(packetBytes);
        this.flowID       = bb.getInt();
        this.packetType   = bb.getShort();
        this.vectorLength = bb.getShort();
        this.codeVector = new short[this.vectorLength];
        for (short i=0; i<this.vectorLength; i++)
            this.codeVector[i] = bb.getShort();
        if (packetType == 1) {
            this.encodedData = new byte[Utils.blockSize];
            bb.get(this.encodedData);
        } else
            this.encodedData = null;
    }
    
    public byte[] bytes() {
        ByteBuffer bb = ByteBuffer.allocate(BASE_SIZE + 2*vectorLength + (encodedData == null ? 0 : encodedData.length));

        bb.putInt(flowID);
        bb.putShort(packetType);
        bb.putShort(vectorLength);
        for (short i=0; i<vectorLength; i++)
            bb.putShort(codeVector[i]);
        if (encodedData != null)
            bb.put(encodedData);
        
        return bb.array();
    }
    
    public static int getFlowID(byte[] bytes) {
        return ByteBuffer.wrap(bytes, 16, 4).getInt();
    }
    
    public static short getPacketType(byte[] bytes) {
        return ByteBuffer.wrap(bytes, 20, 2).getShort();
    }

    
}
