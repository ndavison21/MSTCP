package MSTCP.vegas;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;

public class MSTCPSocket extends Thread {
    private final DatagramSocket socket;
    
    public MSTCPSocket(DatagramSocket socket) {
        this.socket = socket;
    }
    
    public void send(DatagramPacket d) throws IOException {
        socket.send(d);
    }
    
    public void receive(DatagramPacket d) throws IOException {
        socket.receive(d);
    }
    
    public void close() {
        this.interrupt();
    }
    
    @Override
   public void interrupt() {
        super.interrupt();
        this.socket.close();
    }
}
