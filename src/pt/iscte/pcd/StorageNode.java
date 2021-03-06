package pt.iscte.pcd;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Queue;
import java.util.Scanner;

public class StorageNode {
    private static final int DATA_SIZE = 1000000;
    private final String directoryAddress;
    private final int directoryPort;
    private int nodePort;
    private DirectoryClient directory = null;
    private final CloudByte[] data = new CloudByte[DATA_SIZE];
    private ErrorCorrector errorCorrector = null;
    private boolean dataInitialized = false;

    public StorageNode(String directoryAddress, int directoryPort, int nodePort, String dataFilePath) {
        this.directoryAddress = directoryAddress;
        this.directoryPort = directoryPort;
        this.nodePort = nodePort;

        //nodePort == 0 -> system assigned port
        if (directoryPort <= 0 || nodePort < 0 || directoryPort > 0xFFFF || nodePort > 0xFFFF) {
            throw new IllegalArgumentException("Port numbers must be between 0 and " + 0xFFFF);
        }
        if (dataFilePath != null) {
            try {
                readData(dataFilePath);
            } catch (IOException e) {
                e.printStackTrace();
                throw new IllegalArgumentException("Couldn't read data file");
            }
        }
    }

    private void requestData() {
        InetSocketAddress[] nodes = directory.getNodes();
        if (nodes == null || nodes.length == 0) {
            try {
                directory.close();
            } catch (IOException ignored) {
            }
            throw new IllegalStateException("Failed to find nodes for requesting data");
        }
        Queue<ByteBlockRequest> requests;
        requests = new ArrayDeque<>(StorageNode.DATA_SIZE / ByteBlockRequest.BLOCK_LENGTH);
        for (int i = 0; i < StorageNode.DATA_SIZE; i += ByteBlockRequest.BLOCK_LENGTH) {
            ByteBlockRequest req = new ByteBlockRequest(i, ByteBlockRequest.BLOCK_LENGTH);
            requests.add(req);
        }
        System.out.println("Sending " + requests.size() + " requests to " + nodes.length + " nodes");

        SynchronizedRequestQueue<ByteBlockRequest> requestQueue = new SynchronizedRequestQueue<>(requests, nodes.length);

        for (InetSocketAddress node : nodes) {
            System.out.println("Starting download thread for node " + node.getAddress().getHostAddress() + ":" + node.getPort());
            DownloaderThread thread = new DownloaderThread(node, requestQueue, data);
            thread.start();
        }

        try {
            requestQueue.await();
            if (!requestQueue.isComplete())
                throw new IllegalStateException("requestData failed: all download threads died");
        } catch (InterruptedException e) {
            //This should never throw since there's nothing interrupting this call/thread
            throw new IllegalStateException("requestData interrupted");
        }
    }

    private void register() throws IOException {
        directory = new DirectoryClient(directoryAddress, directoryPort, nodePort);
        errorCorrector = new ErrorCorrector(data, directory);
    }

    private void readData(String dataFilePath) throws IOException {
        File dataFile = new File(dataFilePath);
        if (!dataFile.exists() || !dataFile.isFile() || !dataFile.canRead()) {
            throw new IllegalArgumentException("File is invalid or cannot be read");
        }
        byte[] bytes = Files.readAllBytes(dataFile.toPath());
        // Outside source for condition, should this even be an AssertionError?
        if (bytes.length != DATA_SIZE) throw new AssertionError("Expected bytes.length == " + DATA_SIZE);
        for (int i = 0; i < DATA_SIZE; ++i) {
            data[i] = new CloudByte(bytes[i]);
        }
        dataInitialized = true;
        System.out.println("Loaded data from file: \"" + dataFilePath + "\"");
    }

    private void start() {
        try (ServerSocket nodeSocket = new ServerSocket(nodePort)) {
            nodePort = nodeSocket.getLocalPort();
            register();
            if (!dataInitialized) requestData();

            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                if (directory != null) {
                    try {
                        System.out.println("Closing sockets...");
                        directory.close();
                        nodeSocket.close();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }));

            ErrorInjectionThread errorInjectionThread = new ErrorInjectionThread();
            errorInjectionThread.start();

            ErrorCorrectionThread errorCorrectionThread1 = new ErrorCorrectionThread(1);
            ErrorCorrectionThread errorCorrectionThread2 = new ErrorCorrectionThread(2);
            errorCorrectionThread1.start();
            errorCorrectionThread2.start();

            System.out.println("Ready, listening for node connections on port " + nodePort);
            //noinspection InfiniteLoopStatement
            while (true) {
                Socket sock = nodeSocket.accept();
                try {
                    Thread nodeThread = new NodeThread(sock);
                    nodeThread.start();
                } catch (IOException e) {
                    System.err.println("Failed to start node thread");
                    e.printStackTrace();
                }
            }
        } catch (IOException e) {
            System.err.println("Failed to start node socket");
            e.printStackTrace();
        }
        try {
            directory.close();
        } catch (IOException e) {
            System.err.println("Failed to close directory client");
            e.printStackTrace();
        }
    }

    private class ErrorCorrectionThread extends Thread {
        public ErrorCorrectionThread(int id) {
            super("Error Correction Thread-" + id);
        }

        @Override
        public void run() {
            //noinspection InfiniteLoopStatement
            while (true) {
                for (int i = 0; i < data.length; i++) {
                    if (!data[i].isParityOk()) {
                        try {
                            while (!errorCorrector.tryCorrect(i) && !errorCorrector.isCorrecting(i)) {
                                //Failed to correct this error, try again in 1 second
                                sleep(1000);
                            }
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }
                    }
                }
                try {
                    //Try to find and correct errors again after 1 second, to save CPU resources
                    //noinspection BusyWait
                    sleep(1000);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    private class NodeThread extends Thread {
        private final Socket socket;
        private final ObjectOutputStream outStream;
        private final ObjectInputStream inStream;

        public NodeThread(Socket socket) throws IOException {
            super("Node Thread (" + socket.getInetAddress().getHostAddress() + ":" + socket.getPort() + ")");
            this.socket = socket;
            try {
                this.outStream = new ObjectOutputStream(socket.getOutputStream());
                this.inStream = new ObjectInputStream(socket.getInputStream());
            } catch (IOException e) {
                socket.close();
                throw e;
            }
        }

        @Override
        public void run() {
            while (true) {
                try {
                    Object req = inStream.readObject();
                    boolean sentData = false;
                    if (req instanceof ByteBlockRequest) {
                        ByteBlockRequest request = (ByteBlockRequest) req;
                        int start = request.startIndex;
                        int end = start + request.length;
                        if (start >= 0 && start < end && end <= data.length) {
                            try {
                                boolean canSend = true;
                                for (int i = start; i < end; ++i) {
                                    if (!errorCorrector.correct(i)) canSend = false;
                                }
                                if (canSend) {
                                    CloudByte[] dataToSend = Arrays.copyOfRange(data, start, end);
                                    outStream.writeObject(dataToSend);
                                    sentData = true;
                                } else {
                                    System.err.println("Detected errors while sending response, correction failed");
                                }
                            } catch (InterruptedException ignored) {
                            }
                        }
                    }
                    //Prevent remote from blocking if incorrect request received
                    if (!sentData) outStream.writeObject(null);
                } catch (IOException | ClassNotFoundException e) {
                    // EOFException = no more requests
                    if (!(e instanceof EOFException)) e.printStackTrace();
                    break;
                }
            }
            try {
                socket.close();
            } catch (IOException e) {
                System.err.println("Failed to close socket");
                e.printStackTrace();
            }
        }
    }

    private class ErrorInjectionThread extends Thread {
        private final Scanner scanner = new Scanner(System.in);

        public ErrorInjectionThread() {
            super("Error Injection Thread");
        }

        public void run() {
            while (scanner.hasNextLine()) {
                String input = scanner.nextLine();
                if (input == null || input.isEmpty()) continue;
                String[] inputSplit = input.split(" ");
                if (inputSplit.length != 2 || !inputSplit[0].equalsIgnoreCase("ERROR")) {
                    System.out.println("Invalid input, please insert ERROR <byte_num>");
                    continue;
                }
                try {
                    int errorPosition = Integer.parseInt(inputSplit[1]);
                    if (errorPosition < 0 || errorPosition >= DATA_SIZE) {
                        System.err.println("Please enter a position between 0 and " + (DATA_SIZE - 1));
                        continue;
                    }
                    CloudByte dataByte = data[errorPosition];
                    byte before = dataByte.value;
                    dataByte.makeByteCorrupt();
                    System.out.println("Is parity ok? " + dataByte.isParityOk());
                    System.out.println("Successful error insertion: value " + before + " -> " + dataByte.value);
                } catch (NumberFormatException e) {
                    System.err.println("Invalid position for error insertion");
                }
            }
        }
    }

    public static void main(String[] args) {
        if (args.length < 3) {
            System.err.println("Usage: StorageNode <directoryAddr> <directoryPort> <nodePort> [file]");
            return;
        }
        String addr = args[0];
        String dirPortString = args[1];
        String nodePortString = args[2];
        String dataFilePath = args.length > 3 ? args[3] : null;
        if (dataFilePath != null && dataFilePath.isEmpty()) dataFilePath = null;

        try {
            int dirPort = Integer.parseInt(dirPortString);
            int nodePort = Integer.parseInt(nodePortString);
            StorageNode node = new StorageNode(addr, dirPort, nodePort, dataFilePath);
            node.start();
        } catch (NumberFormatException e) {
            System.err.println("Port numbers must be integers");
        }
    }
}
