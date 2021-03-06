package pt.iscte.pcd;

import java.io.*;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

public class DirectoryClient implements Closeable {
    private final Socket directorySocket;
    private final PrintWriter directoryWriter;
    private final BufferedReader directoryReader;

    private final int localPort;

    public DirectoryClient(String directoryAddress, int directoryPort, int localPort) throws IOException {
        this(new Socket(directoryAddress, directoryPort), localPort);
    }

    public DirectoryClient(InetAddress directoryAddress, int directoryPort, int localPort) throws IOException {
        this(new Socket(directoryAddress, directoryPort), localPort);
    }

    private DirectoryClient(Socket sock, int localPort) throws IOException {
        this.directorySocket = sock;
        try {
            this.directoryWriter = new PrintWriter(new OutputStreamWriter(directorySocket.getOutputStream()), true);
            directoryWriter.printf("INSC %s %d\n", directorySocket.getLocalAddress().getHostAddress(), localPort);
            this.directoryReader = new BufferedReader(new InputStreamReader(directorySocket.getInputStream()));
            this.localPort = localPort;
        } catch (IOException e) {
            directorySocket.close();
            throw e;
        }
    }

    @Override
    public void close() throws IOException {
        directorySocket.close();
    }

    /**
     * Requests the list of nodes registered on the directory
     *
     * @return The list of addresses of each node, or null if the connection fails
     */
    public InetSocketAddress[] getNodes() {
        directoryWriter.println("nodes");
        List<String> results = new ArrayList<>();
        try {
            String line;
            while ((line = directoryReader.readLine()) != null && !line.equalsIgnoreCase("end")) results.add(line);
            if (line == null) return null;
        } catch (IOException e) {
            System.err.println("Failed to get list of nodes");
            e.printStackTrace();
            return null;
        }
        List<InetSocketAddress> addresses = new ArrayList<>(results.size());
        for (String v : results) {
            String[] split = v.split(" ");
            if (split.length == 3 && split[0].equalsIgnoreCase("node")) {
                try {
                    InetSocketAddress addr = new InetSocketAddress(split[1], Integer.parseInt(split[2]));
                    // If no address or this node is self then ignore
                    if (addr.isUnresolved() || (addr.getPort() == localPort && addr.getAddress().equals(directorySocket.getLocalAddress())))
                        continue;
                    addresses.add(addr);
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return addresses.toArray(new InetSocketAddress[0]);
    }

    public boolean isConnected() {
        return directorySocket.isConnected();
    }

    @Override
    public String toString() {
        return "DirectoryClient{directory=" +
                directorySocket.getInetAddress().getHostAddress() + ":" + directorySocket.getPort() +
                ", localPort=" + localPort + '}';
    }
}
