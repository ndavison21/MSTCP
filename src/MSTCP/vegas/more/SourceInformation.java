package MSTCP.vegas.more;

import java.nio.ByteBuffer;

public class SourceInformation {
    String address;
    int port;
    boolean connected = false;
    
    public byte[] bytes() {
        ByteBuffer bb = ByteBuffer.allocate(address.getBytes().length + 5);
        bb.put(address.getBytes());
        bb.putInt(port);
        bb.put((byte)(connected ? 1 : 0));
        return bb.array();
    }
     
    public SourceInformation(String address, int port) {
        this.address = address;
        this.port = port;
    }
    
    public SourceInformation(byte[] info) {
        ByteBuffer bb = ByteBuffer.wrap(info);
        byte[] addr = new byte[info.length - 5];
        bb.get(addr);
        this.address = new String(addr);
        this.port = bb.getInt();
        this.connected = bb.get() != 0;
    }
    
    @Override
    public boolean equals(Object o) {
        if (o == this)
            return true;
        if (!(o instanceof SourceInformation))
            return false;
        SourceInformation s = (SourceInformation) o;
        return (this.address.equals(s.address) && this.port == s.port);
    }
    
    @Override
    public int hashCode() {
        int result = 17;
        result = 31 * result + address.hashCode();
        result = 31 * result + port;
        return result;
    }
    
}
