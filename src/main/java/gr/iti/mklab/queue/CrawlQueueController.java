package gr.iti.mklab.queue;

import gr.iti.mklab.simmo.morphia.MorphiaManager;
import org.bson.types.ObjectId;
import org.mongodb.morphia.dao.BasicDAO;
import org.mongodb.morphia.dao.DAO;

import javax.management.*;
import javax.management.remote.JMXConnector;
import javax.management.remote.JMXConnectorFactory;
import javax.management.remote.JMXServiceURL;
import java.io.IOException;
import java.net.DatagramSocket;
import java.net.ServerSocket;
import java.rmi.registry.LocateRegistry;
import java.util.*;
import java.util.concurrent.*;

/**
 * The CrawlQueueController persists submitted crawl requests in a MongoDB,
 * polls in regular intervals and starts a new BUbiNG Agent if there are slots available
 *
 * @author Katerina Andreadou
 */
public class CrawlQueueController {

    public static final String DB_NAME = "crawlerQUEUE";
    private DAO<CrawlRequest, ObjectId> dao;
    private Poller poller;

    /**
     * The number of AVAILABLE_PORTS defines the number of simultaneously running BUbiNG Agents
     */
    private final static Integer[] AVAILABLE_PORTS = {9995};

    public CrawlQueueController() {
        // Sets up the Morphia Manager
        MorphiaManager.setup(DB_NAME);
        // Creates a DAO object to persist submitted crawl requests
        dao = new BasicDAO<CrawlRequest, ObjectId>(CrawlRequest.class, MorphiaManager.getMongoClient(), MorphiaManager.getMorphia(), MorphiaManager.getDB().getName());
        // Starts a polling thread to regularly check for empty slots
        poller = new Poller();
        poller.startPolling();
    }

    public void shutdown() {
        MorphiaManager.tearDown();
        poller.stopPolling();
    }

    /**
     * Submits a new crawl request
     *
     * @param crawlDir
     * @param collectionName
     */
    public synchronized void submit(String crawlDir, String collectionName) {
        System.out.println("submit event");
        enqueue(crawlDir, collectionName);
        tryLaunch();
    }

    /**
     * Cancels the BUbiNG Agent listening to the specified port
     *
     * @param portNumber
     */
    public void cancel(int portNumber) {
        try {
            //JMXServiceURL jmxServiceURL = new JMXServiceURL("service:jmx:rmi://localhost/jndi/rmi://localhost:9999/jmxrmi");
            JMXServiceURL jmxServiceURL = new JMXServiceURL("service:jmx:rmi:///jndi/rmi://127.0.0.1:" + portNumber + "/jmxrmi");
            JMXConnector cc = JMXConnectorFactory.connect(jmxServiceURL);
            MBeanServerConnection mbsc = cc.getMBeanServerConnection();
            //This information is available in jconsole
            ObjectName serviceConfigName = new ObjectName("it.unimi.di.law.bubing:type=Agent,name=agent");
            //  Invoke stop operation
            mbsc.invoke(serviceConfigName, "stop", null, null);
            //  Close JMX connector
            cc.close();
        } catch (Exception e) {
            System.out.println("Exception occurred: " + e.toString());
            e.printStackTrace();
        }
    }

    private void enqueue(String crawlDir, String collectionName) {
        CrawlRequest r = new CrawlRequest();
        r.collectionName = collectionName;
        r.requestState = CrawlRequest.STATE.WAITING;
        r.lastStateChange = new Date();
        r.creationDate = new Date();
        r.crawlDataPath = crawlDir;
        dao.save(r);
    }

    private void tryLaunch() {
        List<CrawlRequest> list = getRunningCrawls();
        // Make a copy of the available port numbers
        List<Integer> ports = new LinkedList<Integer>(Arrays.asList(AVAILABLE_PORTS));
        // and find a non-used port
        System.out.println("Running crawls list size " + list.size());
        for (CrawlRequest r : list) {
            System.out.println("Port " + r.portNumber);
            if (ports.contains(r.portNumber)) {
                // Check if the Agent on that port has finished or failed
                // without updating the DB
                if (isPortAvailable(r.portNumber)) {
                    System.out.println("Available");
                    r.requestState = CrawlRequest.STATE.FINISHED;
                    r.lastStateChange = new Date();
                    dao.save(r);
                }
                // The port is really busy so remove it from the list of available ports
                else {
                    System.out.println("Not available");
                    ports.remove(new Integer(r.portNumber));
                }
            }
        }
        for (Integer i : ports) {
            System.out.println("Try launch crawl for port " + i);
            // Check if port is really available, if it is launch the respective script
            if (isPortAvailable(i)) {
                launch("crawl" + i + ".sh");
                break;
            }
            System.out.println("Port " + i + " is not available");
        }
    }

    private void launch(String scriptName) {
        try {
            String[] command = {"/bin/bash", scriptName};
            ProcessBuilder p = new ProcessBuilder(command);
            Process pr = p.start();
        } catch (IOException ioe) {
            System.out.println("Problem starting process for scriptName " + scriptName);
        }
    }

    public class Poller implements Runnable {
        final ScheduledExecutorService exec = Executors.newSingleThreadScheduledExecutor();
        Future future = null;

        @Override
        public void run() {
            System.out.println("polling event");
            tryLaunch();
        }

        public void startPolling() {
            exec.scheduleAtFixedRate(this, 10, 20, TimeUnit.SECONDS);
        }

        public void stopPolling() {
            if (future != null && !future.isDone())
                future.cancel(true);
            exec.shutdownNow();
        }
    }

    private List<CrawlRequest> getRunningCrawls() {
        return dao.getDatastore().find(CrawlRequest.class).filter("requestState", CrawlRequest.STATE.RUNNING).asList();
    }

    private boolean isPortAvailable(int port) {

        ServerSocket ss = null;
        DatagramSocket ds = null;
        try {
            ss = new ServerSocket(port);
            ss.setReuseAddress(true);
            ds = new DatagramSocket(port);
            ds.setReuseAddress(true);
            return true;
        } catch (IOException e) {
        } finally {
            if (ds != null) {
                ds.close();
            }

            if (ss != null) {
                try {
                    ss.close();
                } catch (IOException e) {
                /* should not be thrown */
                }
            }
        }

        return false;
    }
}