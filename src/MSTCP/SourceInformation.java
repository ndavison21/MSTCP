package MSTCP;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;

public class SourceInformation {
    InetAddress address;
    int port;
    
    public byte[] bytes() {
        String addr = address.getHostAddress();
        ByteBuffer bb = ByteBuffer.allocate(addr.getBytes().length + 4);
        bb.put(addr.getBytes());
        return bb.array();
    }
    
    public SourceInformation(InetAddress address, int port) {
        this.address = address;
        this.port = port;
    }
    
    public SourceInformation(String address, int port) throws UnknownHostException {
        this(InetAddress.getByName(address), port);
    }
    
    public SourceInformation(byte[] info) throws UnknownHostException {
        ByteBuffer bb = ByteBuffer.wrap(info);
        byte[] addr = new byte[info.length - 4];
        bb.get(addr);
        address = InetAddress.getByName(new String(addr));
        this.port = bb.getInt();
    }
}
