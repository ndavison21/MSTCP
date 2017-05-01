package MSTCP.vegas.more;

import java.nio.ByteBuffer;
import java.util.HashMap;

public class SourceInformation {
    String address;
    HashMap<Integer, Boolean> ports = new HashMap<Integer, Boolean>();
    byte connected = 0;
    
    public byte[] bytes() {
        ByteBuffer bb = ByteBuffer.allocate(4 + address.getBytes().length + (ports.size() * 5) + 1);
        bb.putInt(address.getBytes().length);
        bb.put(address.getBytes());
        for (Integer port: ports.keySet()) {
            bb.putInt(port);
            bb.put((byte) (ports.get(port) ? 1 : 0));
        }
        bb.put(connected);
        return bb.array();
    }
     
    public SourceInformation(String address, int[] ports) {
        this.address = address;
        this.ports = new HashMap<Integer, Boolean>(ports.length);
        for (int port: ports) {
            this.ports.put(port, false);
        }
    }
    
    public SourceInformation(byte[] info) {
        this.ports = new HashMap<Integer, Boolean>();
        
        ByteBuffer bb = ByteBuffer.wrap(info);
        byte[] addr = new byte[bb.getInt()]; // length array returned by InetAddress.getBytes()
        bb.get(addr);
        this.address = new String(addr);
        while (bb.remaining() > 1)
            this.ports.put(bb.getInt(), bb.get() == 1);
        this.connected = bb.get();
    }
    
    @Override
    public boolean equals(Object o) {
        if (o == this)
            return true;
        if (!(o instanceof SourceInformation))
            return false;
        SourceInformation s = (SourceInformation) o;
        return this.address.equals(s.address);
    }
    
}
