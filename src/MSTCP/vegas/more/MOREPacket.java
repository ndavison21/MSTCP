package MSTCP.vegas.more;

import java.nio.ByteBuffer;

public class MOREPacket {
    private final int block;
    private final byte[] data;
    
    public MOREPacket(int block) {
        this.block = block;
        this.data = null;
    }
    
    public MOREPacket(int block, byte[] data) {
        this.block = block;
        this.data = data;
    }
    
    public MOREPacket(byte[] data) {
        this.block = ByteBuffer.wrap(data, 0, 4).getInt();
        this.data = new byte[data.length - 4];
        ByteBuffer.wrap(data, 4, data.length).get(this.data);
        
    }
    
    public int getBlock() {
        return block;
    }
    public byte[] getData() {
        return data;
    }
    
    public byte[] bytes() {
        ByteBuffer bb = ByteBuffer.allocate(4 + data.length);
        bb.putInt(block);
        bb.put(data);
        return bb.array();
    }
    
}
