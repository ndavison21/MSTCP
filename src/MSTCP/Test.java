package MSTCP;

import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.Vector;
import java.util.concurrent.TimeUnit;

public class Test {
    public static void main(String[] args) throws InterruptedException, SocketException, UnknownHostException {
        final Vector<SourceInformation> sources = new Vector<SourceInformation>();
        sources.add(new SourceInformation("127.0.0.1", 15000));
        sources.add(new SourceInformation("127.0.0.1", 15001));
        
        (new Thread() {
            public void run() {
                new MSTCPSender(15000, "./", sources);
            }
        }).start();
        
        (new Thread() {
            public void run() {
                new MSTCPSender(15001, "./", sources);
            }
        }).start();
        
        TimeUnit.SECONDS.sleep(2);
        
        //new MSTCPReceiver("127.0.0.1", 14000, 15000, "./", "hello_repeat.txt"); // recvPort, dstPort
        new MSTCPReceiver("127.0.0.1", 14000, 15000, "./", "me.jpg"); // recvPort, dstPort
        //new MSTCPReceiver("127.0.0.1", 14000, 15000, "./", "me.jpg"); // recvPort, dstPort
    }
}
