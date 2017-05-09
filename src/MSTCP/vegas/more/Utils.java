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
    public static Logger logger;
    
    public static final int DATA_ENUM = 0;
    public static final int SYN_ENUM  = 1;
    public static final int FIN_ENUM  = 2;
    
    
    public static final boolean logging = true; // whether to do logging
    public static final boolean debug   = false; // turn off timeouts so can step through with debug mode
    public static final boolean decode  = false; // perform decoding of packets at received
    public static final boolean recode  = false; // perform recoding of packets in the network

    public static final boolean localhost    = true;  // get public or local IP
    public static final Random rand          = new Random();
    
    public static final double p_smooth      = 0.0001;   // smoothing factor for monitoring drop rate

    public static int noOfPaths       = 2;  // number of paths available to each source
    public static int noOfSources     = 2;  // number of sources available
    public static int noOfConnections = 2;  // number of connections the requester should start up
    public static double p_drop       = 0.00501256; // drop rate (over 2 legs gives 0.01)
    public static int packetLimit     = Integer.MAX_VALUE;
    
    public static int batchSize     = 16;   // to keep matrix sizes small we send blocks in smaller groups
    public static final int pktSize       = 1000; // 1000 Bytes total (Header 28 bytes, Block 972 bytes)
    public static final int tcpSize       = 28;   // TCP Header no options
    public static final int moreSize      = 12;   // MORE Header with no code vector or data
    public static final int blockSize     = 800;  // size of data blocks
    public static final int transferSize  = blockSize + 1; // we prefix a byte to avoid bytes being dropped when converting to BigInteger
    public static final int maxVectorSize = pktSize - (tcpSize + moreSize + blockSize); // maximum size of code vector in bytes
    public static final int precision     = 2000; // number of decimal places to calculate to  (must be greater than log10(2 ^ blockSize) )
    

    // retransmit parameters
    public static final int synAttempts  = 30;
    public static final int synTimeout   = debug ? Integer.MAX_VALUE : 500;
    public static final int dataAttempts = 3;
    public static final int dataTimeout  = debug ? Integer.MAX_VALUE : 1000;
    public static final int finAttempts  = 3;
    public static final int finTimeout   = debug ? Integer.MAX_VALUE : 200;

    // congestion control parameter TODO: understand what this represents
    public static final int total_alpha = 10;
    public static final int gamma       = 5; // parameter for exiting slow starting

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
            e.printStackTrace();
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
            e.printStackTrace();
            System.exit(1);
        }

        return address;
    }
    
    
    
    public static Logger getLogger(String filename) {
        return getLogger(filename, "", Utils.logging ? Level.ALL: Level.OFF );
    }
    
    public static Logger getLogger(String filename, String path, Level level) {
        return getLogger(filename, path, level, 1048576);
    }

    public static Logger getLogger(String filename, String path, Level level, int limit) {
        Logger logger = Logger.getLogger(filename);
        try {
            FileHandler handler = new FileHandler("./logs/" + path + filename + ".log", limit, 1, true);
            handler.setFormatter(new SimpleFormatter());
            logger.setUseParentHandlers(false);
            logger.addHandler(handler);
            logger.setLevel(level);
        } catch (SecurityException | IOException e) {
            System.err.println("MSTCPReceiver: Unable to Connect to Logger");
            e.printStackTrace();
            return null;
        }
        return logger;
    }
}
