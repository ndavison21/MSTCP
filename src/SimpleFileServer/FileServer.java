package SimpleFileServer;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;

/**
 * 
 * @author Nathanael Davison - nd359
 *
 *      A simple file server which receives a request for a file by name and sends it if the file exists
 */
public class FileServer {
    private static ServerSocket serverSocket;
    private static Socket clientSocket = null;
	
    private static int port = 14415;
    // private static String hostname = "127.0.0.1";
	
    public static void main(String[] args) throws IOException {
        serverSocket = new ServerSocket(port); // Connect to port
        System.out.println("FileServer: Server Started");
		
        while (true) {
            clientSocket = serverSocket.accept(); // listens for connections and accepts if there is one
            System.out.println("File Server: Accepted Connect: " + clientSocket);
            Thread t = new Thread(new ClientConnection(clientSocket)); // start a new thread to handle the connection
            t.start();
        }
    }
}
