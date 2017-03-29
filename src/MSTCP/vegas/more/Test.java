package MSTCP.vegas.more;

import java.io.IOException;
import java.util.Vector;
import java.util.concurrent.TimeUnit;

/**
 * VM ARGUMENT: -Djava.util.logging.SimpleFormatter.format="%1$tY-%1$tm-%1$td %1$tH:%1$tM:%1$tS %4$s %2$s %5$s%6$s%n"
 */
public class Test {
    public static void main(String[] args) throws InterruptedException, IOException {
        try {
            System.out.println("Starting Test.");
            
            final Vector<SourceInformation> sources = new Vector<SourceInformation>();
            sources.add(new SourceInformation(Utils.getIPAddress(null), 15000));
            sources.add(new SourceInformation(Utils.getIPAddress(null), 15001));
            
            (new Thread() {
                public void run() {
                    new MSTCPResponder(Utils.getIPAddress(null), 15000, "./", sources);
                }
            }).start();
            
            (new Thread() {
                public void run() {
                    new MSTCPResponder(Utils.getIPAddress(null), 15001, "./", sources);
                }
            }).start();
            
            TimeUnit.SECONDS.sleep(2);
            
            // new MSTCPRequester(Utils.getIPAddress(null), Utils.getIPAddress(null),  14000, 15000, "./", "hello.txt");
            new MSTCPRequester(Utils.getIPAddress(null), Utils.getIPAddress(null), 14000, 15000, "./", "hello_repeat.txt");
            // new MSTCPRequester(Utils.getIPAddress(null), Utils.getIPAddress(null),  14000, 15000, "./", "hello_repeat_repeat.txt");
            // new MSTCPRequester(Utils.getIPAddress(null), Utils.getIPAddress(null),  14000, 15000, "./", "me.jpg");
        
            System.out.println("Test Complete.");
        } catch (Exception e) {
            e.printStackTrace();
            System.exit(1);
        }
    }
}
