package MSTCP.vegas.more;

import java.math.BigInteger;
import java.nio.ByteBuffer;

public class MOREPacket {
    static int BASE_SIZE = Utils.moreSize * 8; // base size of the packet in bits without code vector or encoded data
    
    public static short FORWARD_PACKET = 0;
    public static short RETURN_PACKET  = 1;
    

    private int   flowID     = -1;  // 32 bits, flow identifier, hopefully unique
    private short packetType = -1;  // 16 bits, (0 means forward packet, 1 means response packet)

    
    private short batch;        // 16 bits, batches of the file being sent    
    private short vectorLength; // 16 bits, number of elements in the codeVector
    private short dataLength;   // 16 bits, number of bytes in the encodedData
    private CodeVectorElement[] codeVector;    // array of 16 bit shorts, blocks and coefficients of encoded data.
    private BigInteger encodedData;    // ? bits, combined packets
    
    public MOREPacket(int flowID, short packetType, short batch, CodeVectorElement[] codeVector, BigInteger encodedData) {
        super();
        this.flowID = flowID;
        this.packetType = packetType;
        this.batch = batch;
        this.vectorLength = (short) (codeVector == null ? 0 : codeVector.length);
        this.dataLength = (short) (encodedData == null ? 0 : encodedData.toByteArray().length);
        this.codeVector = codeVector;
        this.encodedData = encodedData;
    }
    
    public MOREPacket clone() {
        return new MOREPacket(this.bytes());
    }
    
    public MOREPacket(int flowID, short packetType, short batch, CodeVectorElement[] codeVector, byte[] encodedData) {
        this(flowID, packetType, batch, codeVector, new BigInteger(encodedData));
    }
    
    public MOREPacket(int flowID, short packetType, short batch, CodeVectorElement[] codeVector) {
        this(flowID, packetType, batch, codeVector, (BigInteger) null);
    }
    
    public MOREPacket(int flowID, short packetType) {
        this(flowID, packetType, (short) -1, null);
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
    
    public short getBatch() {
        return batch;
    }

    public void setBatch(short batch) {
        this.batch = batch;
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
    
    public short getDataLength() {
        return dataLength;
    }

    public BigInteger getEncodedData() {
        return encodedData;
    }

    public void setEncodedData(byte[] encodedData) {
        this.dataLength = (short) (encodedData == null ? 0 : encodedData.length);
        this.encodedData = new BigInteger(encodedData);
    }
    
    public void setEncodedData(BigInteger encodedData) {
        this.dataLength = (short) (encodedData == null ? 0 : encodedData.toByteArray().length);
        this.encodedData = encodedData;
    }
    
    
    public MOREPacket(byte[] packetBytes) {
        ByteBuffer bb = ByteBuffer.wrap(packetBytes);
        this.flowID       = bb.getInt();
        this.packetType   = bb.getShort();
        this.batch        = bb.getShort();
        this.vectorLength = bb.getShort();
        this.dataLength   = bb.getShort();
        this.codeVector = new CodeVectorElement[this.vectorLength];
        for (short i=0; i<this.vectorLength; i++)
            this.codeVector[i] = new CodeVectorElement(bb.getShort(), bb.getShort());
        if (packetType == 1) {
            byte[] encodedDataBytes = new byte[dataLength];
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
        bb.putShort(batch);
        bb.putShort(vectorLength);
        bb.putShort(dataLength);
        for (short i=0; i<vectorLength; i++)
            bb.put(codeVector[i].bytes());
        if (encodedData != null)
            bb.put(encodedData.toByteArray());
        
        return bb.array();
    }
    
    public static int getFlowID(byte[] moreBytes) {
        return ByteBuffer.wrap(moreBytes, 0, 4).getInt();
    }
    
    public static short getPacketType(byte[] moreBytes) {
        return ByteBuffer.wrap(moreBytes, 4, 2).getShort();
    }
    

    
}
