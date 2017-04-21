package MSTCP.vegas.more;

import java.io.File;
import java.io.IOException;
import java.net.SocketException;
import java.nio.file.Files;
import java.util.Vector;
import java.util.concurrent.TimeUnit;

/**
 * VM ARGUMENT: -Djava.util.logging.SimpleFormatter.format="%1$tY-%1$tm-%1$td %1$tH:%1$tM:%1$tS %4$s %2$s %5$s%6$s%n"
 */
public class Test {
    public static void triangle() throws InterruptedException, IOException {
        try {
            System.out.println("Starting Test.");
            
            final Vector<SourceInformation> sources = new Vector<SourceInformation>();
            sources.add(new SourceInformation(Utils.getIPAddress(null), 16000));
            sources.add(new SourceInformation(Utils.getIPAddress(null), 16001));
            
            (new Thread() {
                public void run() {
                    new MSTCPResponder(Utils.getIPAddress(null), 16000, "./", sources);
                }
            }).start();
            
            (new Thread() {
                public void run() {
                    new MSTCPResponder(Utils.getIPAddress(null), 16001, "./", sources);
                }
            }).start();
            
            (new Thread() {
                public void run() {
                    try {
                        new MSTCPForwarder(Utils.router_port);
                    } catch (SocketException e) {
                        e.printStackTrace();
                        System.exit(1);
                    }   
                }
            }).start();
            
            TimeUnit.SECONDS.sleep(2);
            
            // String file = "hello.txt";
            // String file = "hello_800.txt";
            String file = "hello_repeat.txt";
            // String file = "hello_repeat_repeat.txt";
            // String file = "me.jpg";
            
            new MSTCPRequester(Utils.getIPAddress(null), Utils.getIPAddress(null), 14000, 16000, "./", file);
        
            File original = new File("./" + file);
            File received = new File("./received_" + file);
            
            byte[] o = Files.readAllBytes(original.toPath());
            byte[] r = Files.readAllBytes(received.toPath());
            
            for (int i=0; i<o.length; i++) {
                if (o[i] != r[i]) {
                    System.out.println("Difference at byte " + i + ". Original is " + o[i] + ", Received is " + r[i]);
                }
            }
            
            if (o.length > r.length)
                System.out.println("Original file contains more bytes.");
            else if (r.length > o.length)
                System.out.println("Received file contains more bytes.");
            
            System.out.println("Test Complete.");
        } catch (Exception e) {
            e.printStackTrace();
            System.exit(1);
        }
        
    }
    
    public static void nullTcp() {
        TCPPacket pkt1 = new TCPPacket(null);
        TCPPacket pkt2 = new TCPPacket(new byte[0]);
    }
    
    public static void main(String[] args) throws InterruptedException, IOException {
        triangle();
        // nullTcp();
    }
}
