package MSTCP.vegas.more;

import java.io.File;
import java.io.IOException;
import java.net.SocketException;
import java.util.Vector;
import java.util.concurrent.TimeUnit;

/**
 * VM ARGUMENT: -Djava.util.logging.SimpleFormatter.format="%1$tY-%1$tm-%1$td %1$tH:%1$tM:%1$tS %4$s %2$s %5$s%6$s%n"
 */
public class Test {
    public static void triangle() throws InterruptedException, IOException {
        try {
            System.out.println("Starting Test.");
            
            final Vector<SourceInformation> sources = new Vector<SourceInformation>(2);
            sources.add(new SourceInformation("127.0.0.1", new int[] {16000, 16001}));
            sources.add(new SourceInformation("127.0.0.2", new int[] {16002, 16003}));
            
            // Creating sources
            int i = 0;
            for (final SourceInformation source: sources) {
                final int routerPort = 15005 + i;
               // final int routerPort = 15000 + i;
                for (final int port: source.ports.keySet()) {
                    (new Thread() {
                        public void run() {
                            new MSTCPResponder(source.address, port, routerPort, "./", sources);
                        }
                    }).start();
                }
                i++;
            }
            
            
            // Creating Routers
            (new Thread() {
                public void run() {
                    try {
                        new RequesterForwarder(15000);
                    } catch (SocketException e) {
                        e.printStackTrace();
                        System.exit(1);
                    }   
                }
            }).start();
            
            for (i=1; i<5; i++) {
                final int port1 = 15000 + i;
                (new Thread() {
                    public void run() {
                        try {
                            //new MiddleForwarder(port1, 3, 0.01);
                            new MiddleForwarder(port1, 0, 0.683772);//292893);
                        } catch (SocketException e) {
                            e.printStackTrace();
                            System.exit(1);
                        }   
                    }
                }).start();
                i++;
                final int port2 = 15000 + i;
                (new Thread() {
                    public void run() {
                        try {
                            //new MiddleForwarder(port2, 10, 0.1);
                            new MiddleForwarder(port2, 0, 0.683772);
                        } catch (SocketException e) {
                            e.printStackTrace();
                            System.exit(1);
                        }   
                    }
                }).start();
            }
            
            for (i=5; i<7; i++) {
                final int port = 15000 + i;
                (new Thread() {
                    public void run() {
                        try {
                            new ResponderForwarder(port);
                        } catch (SocketException e) {
                            e.printStackTrace();
                            System.exit(1);
                        }   
                    }
                }).start();
                
            }
            
            TimeUnit.SECONDS.sleep(2);
            
            String path = "./evaluation/data/throughput/s1_c2_b16";
            
            File logs = new File("./logs");
            for(File log: logs.listFiles()) 
                if (!log.isDirectory()) 
                    log.delete();
            
            logs = new File(path);
            for(File log: logs.listFiles()) 
                if (!log.isDirectory()) 
                    log.delete();
            
            
            // String[] files = new String[]{"hello.txt", "hello_800.txt", "hello_repeat.txt", "hello_repeat_repeat.txt", "me.jpg"};
            String file = "hello_repeat_repeat.txt";
            //String file = "gb.jpg";
            
            for(i=0; i<1; i++) {
//                Utils.logger =  Utils.getLogger("experiment_" + i, "." + path + "/", Level.FINE);
                
                long start = System.currentTimeMillis();
                System.out.println("#" + i + " Starting Transfer of " + file + ". Started at " + start);
                
                new MSTCPRequester(Utils.getIPAddress(null), Utils.getIPAddress(null), 14000, 16000, "./", file);
                System.out.println("Transfer Complete. Took " + (System.currentTimeMillis() - start) + "ms");
                System.gc();
                
                for (SourceInformation s: sources) {
                    for (int port: s.tried.keySet())
                        s.tried.put(port, false);
                }
                
            }
            
            System.out.println("Test Complete.");
        } catch (Exception e) {
            e.printStackTrace();
            System.exit(1);
        } finally {
            System.exit(1);
        }
        
    }
    

    
    public static void main(String[] args) throws InterruptedException, IOException {
        File logs = new File("./logs");
        for(File file: logs.listFiles()) 
            if (!file.isDirectory()) 
                file.delete();
        triangle();
    }
}
