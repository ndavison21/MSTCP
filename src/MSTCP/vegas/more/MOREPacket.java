package MSTCP.vegas.more;

import java.math.BigInteger;
import java.nio.ByteBuffer;

public class MOREPacket {
    static int BASE_SIZE = Utils.moreSize * 8; // base size of the packet in bits without code vector or encoded data
    

    private int   flowID     = -1;  // 32 bits, flow identifier, hopefully unique
    private short packetType = -1;  // 16 bits, (0 means forward packet, 1 means response packet)

    private short vectorLength;      // 16 bits, number of elements in the codeVector
    private CodeVectorElement[] codeVector;    // array of 16 bit shorts, blocks and coefficients of encoded data.
    private BigInteger encodedData;    // ? bits, combined packets
    
    public MOREPacket(int flowID, short packetType, CodeVectorElement[] codeVector, BigInteger encodedData) {
        super();
        this.flowID = flowID;
        this.packetType = packetType;
        this.vectorLength = (short) (codeVector == null ? 0 : codeVector.length);
        this.codeVector = codeVector;
        this.encodedData = encodedData;
    }
    
    public MOREPacket(int flowID, short packetType, CodeVectorElement[] codeVector, byte[] encodedData) {
        this(flowID, packetType, codeVector, new BigInteger(encodedData));
    }
    
    public MOREPacket(int flowID, CodeVectorElement[] codeVector) {
        this(flowID, (short) 0, codeVector, (BigInteger) null);
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

    public CodeVectorElement[] getCodeVector() {
        return codeVector;
    }

    public void setCodeVector(CodeVectorElement[] codeVector) {
        this.vectorLength = (short) (codeVector == null ? 0 : codeVector.length);
        this.codeVector = codeVector;
    }

    public BigInteger getEncodedData() {
        return encodedData;
    }

    public void setEncodedData(byte[] encodedData) {
        this.encodedData = new BigInteger(encodedData);
    }
    
    public void setEncodedData(BigInteger encodedData) {
        this.encodedData = encodedData;
    }
    
    
    public MOREPacket(byte[] packetBytes) {
        ByteBuffer bb = ByteBuffer.wrap(packetBytes);
        this.flowID       = bb.getInt();
        this.packetType   = bb.getShort();
        this.vectorLength = bb.getShort();
        this.codeVector = new CodeVectorElement[this.vectorLength];
        for (short i=0; i<this.vectorLength; i++)
            this.codeVector[i] = new CodeVectorElement(bb.getShort(), bb.getShort());
        if (packetType == 1) {
            byte[] encodedDataBytes = new byte[bb.remaining()];
            bb.get(encodedDataBytes);
            this.encodedData = new BigInteger(encodedDataBytes);
        } else
            this.encodedData = null;
    }
    
    public byte[] bytes() {
    	byte[] encodedDataBytes = (encodedData == null ? new byte[0] : encodedData.toByteArray());
        ByteBuffer bb = ByteBuffer.allocate(BASE_SIZE + 2*vectorLength + encodedDataBytes.length);

        bb.putInt(flowID);
        bb.putShort(packetType);
        bb.putShort(vectorLength);
        for (short i=0; i<vectorLength; i++)
            bb.put(codeVector[i].bytes());
        if (encodedData != null)
            bb.put(encodedData.toByteArray());
        
        return bb.array();
    }
    
    public static int getFlowID(byte[] bytes) {
        return ByteBuffer.wrap(bytes, 16, 4).getInt();
    }
    
    public static short getPacketType(byte[] bytes) {
        return ByteBuffer.wrap(bytes, 20, 2).getShort();
    }

    
}
