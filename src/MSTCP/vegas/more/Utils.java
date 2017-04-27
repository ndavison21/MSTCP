package MSTCP.vegas.more;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.URL;
import java.util.Random;
import java.util.logging.FileHandler;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

public final class Utils {
    public static final int DATA_ENUM = 0;
    public static final int SYN_ENUM  = 1;
    public static final int FIN_ENUM  = 2;
    
    public static final boolean debug = true; // turn off timeouts so can step through with debug mode

    public static final boolean localhost    = true;  // get public or local IP
    public static final Random rand          = new Random();
    public static final int bufferSize       = 3;     // buffer size (drop packets received when buffer is full)
    public static final int latency          = 0;  // artificial mean latency
    public static final int latency_variance = 0;  // artificial range of latency
    public static final double p_drop        = 0;  // artificial drop late (drop if rand is less than this)
    public static final boolean router       = true;  // send packets to router (not destination)
    public static final int router_port      = 15000; // port router is connected to
    
    public static final double p_smooth      = 0.2;   // smoothing factor for monitoring drop rate

    public static final int noOfSources   = 1;
    public static final int batchSize     = 16;    // to keep matrix sizes small we send blocks in smaller groups
    public static final int pktSize       = 1000; // 1000 Bytes total (Header 28 bytes, Block 972 bytes)
    public static final int tcpSize       = 28;   // TCP Header no options
    public static final int moreSize      = 10;   // MORE Header with no code vector or data
    public static final int blockSize     = 800;  // size of data blocks
    public static final int transferSize  = blockSize + 1; // we prepend a byte containing '1' to ensure not bytes are truncated
    public static final int maxVectorSize = pktSize - (tcpSize + moreSize + blockSize); // maximum size of code vector in bytes
    public static final int precision     = 2000; // number decimal places to calculate to  (must be greater than log10(2 ^ blockSize) )
    

    // retransmit parameters
    public static final int synAttempts  = 30;
    public static final int synTimeout   = debug ? Integer.MAX_VALUE : 500;
    public static final int dataAttempts = 3;
    public static final int dataTimeout  = debug ? Integer.MAX_VALUE : 1000;
    public static final int finAttempts  = 3;
    public static final int finTimeout   = debug ? Integer.MAX_VALUE : 500;

    // congestion control parameter
    public static final int total_alpha = 10;

    public static String getIPAddress(Logger logger) {
        if (localhost)
            return "127.0.0.1";
        
        URL req;
        BufferedReader in;
        String addr = null;

        try {
            req = new URL("http://checkip.amazonaws.com");
            in = new BufferedReader(new InputStreamReader(req.openStream()));
            addr = in.readLine(); // gets the IP as a string
        } catch (IOException e) {
            logger.log(Level.SEVERE, e.getMessage(), e);
            System.exit(1);
        }

        return addr;
        
    }
    
    public static InetAddress getInetAddress(Logger logger) {
        URL req;
        BufferedReader in;
        String addr = null;
        InetAddress address = null;
        
        try {
            if (localhost)
                address = InetAddress.getByName("127.0.0.1");
            else {
                req = new URL("http://checkip.amazonaws.com");
                in = new BufferedReader(new InputStreamReader(req.openStream()));
                addr = in.readLine(); // gets the IP as a string
                address = InetAddress.getByName(addr);
            }
        } catch (IOException e) {
            logger.log(Level.SEVERE, e.getMessage(), e);
            System.exit(1);
        }

        return address;
    }

    public static boolean drop() {
        return (rand.nextDouble() < p_drop);
    }

    public static void delay(Logger logger) {
        try {
            if (Utils.latency > 0)
                Thread.sleep(latency + rand.nextInt(latency_variance) - latency_variance / 2);
        } catch (InterruptedException e) {
            logger.log(Level.SEVERE, e.getMessage(), e);
            System.exit(1);
        }
    }

    public static Logger getLogger(String filename) {
        Logger logger = Logger.getLogger(filename);
        try {
            FileHandler handler = new FileHandler("./logs/" + filename + ".log", 1048576, 1, true);
            handler.setFormatter(new SimpleFormatter());
            logger.setUseParentHandlers(false);
            logger.addHandler(handler);
            logger.setLevel(Level.ALL);
        } catch (SecurityException | IOException e) {
            System.err.println("MSTCPReceiver: Unable to Connect to Logger");
            e.printStackTrace();
            return null;
        }
        return logger;
    }
}
