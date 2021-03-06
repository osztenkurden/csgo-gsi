package uk.oczadly.karl.csgsi;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.oczadly.karl.csgsi.internal.Util;
import uk.oczadly.karl.csgsi.internal.httpserver.HTTPServer;
import uk.oczadly.karl.csgsi.state.GameState;

import java.io.IOException;
import java.net.InetAddress;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * This class is used to listen for live game state information as sent by the game client.
 * <p>
 * The listening network port is configured within the class constructor, and the server is started through the {@link
 * #startServer()} method. Observers can be registered through the {@link #registerObserver(GSIObserver)} method, which
 * subscribes the object to new game state information as it is received.
 */
public final class GSIServer {
    
    private static final Logger LOGGER = LoggerFactory.getLogger(GSIServer.class);
    private static final Gson GSON = Util.createGsonObject();
    
    private final HTTPServer server;
    private final Set<GSIObserver> observers = new CopyOnWriteArraySet<>();
    private final ExecutorService observerExecutor = Executors.newFixedThreadPool(50);
    private final Map<String, String> requiredAuthTokens;
    private volatile boolean discardOlderStates = true;
    
    private volatile GameState latestGameState;
    private final Map<InetAddress, Integer> latestTimestamps = new ConcurrentHashMap<>();
    
    
    /**
     * Constructs a new GSIServer object with pre-processed client authentication.
     *
     * @param port               the network port for the server to listen on
     * @param bindAddr           the local address to bind to
     * @param requiredAuthTokens the authentication tokens required to accept state reports
     */
    public GSIServer(int port, InetAddress bindAddr, Map<String, String> requiredAuthTokens) {
        //Validate port
        if (port <= 0 || port > 65535)
            throw new IllegalArgumentException("Port number out of range");
        
        //Validate auth tokens
        if (requiredAuthTokens != null) {
            for (Map.Entry<String, String> key : requiredAuthTokens.entrySet()) {
                if (key.getKey() == null || key.getValue() == null)
                    throw new IllegalArgumentException("Auth token key or value cannot be null");
            }
            this.requiredAuthTokens = Collections.unmodifiableMap(new HashMap<>(requiredAuthTokens));
        } else {
            this.requiredAuthTokens = Collections.unmodifiableMap(new HashMap<>());
        }
        
        this.server = new HTTPServer(port, bindAddr, 1,
                (address, path, method, headers, body) -> handleStateUpdate(body.trim(), address));
    }
    
    /**
     * Constructs a new GSIServer object with pre-processed client authentication.
     *
     * @param port               the network port for the server to listen on
     * @param requiredAuthTokens the authentication tokens required to accept state reports
     */
    public GSIServer(int port, Map<String, String> requiredAuthTokens) {
        this(port, null, requiredAuthTokens);
    }
    
    /**
     * Constructs a new GSIServer object with no pre-processed client authentication.
     *
     * @param port     the network port for the server to listen on
     * @param bindAddr the local address to bind to
     */
    public GSIServer(int port, InetAddress bindAddr) {
        this(port, bindAddr, new HashMap<>());
    }
    
    /**
     * Constructs a new GSIServer object with no pre-processed client authentication.
     *
     * @param port the network port for the server to listen on
     */
    public GSIServer(int port) {
        this(port, (InetAddress)null);
    }
    
    
    /**
     * @return the latest game state, or null if the server has yet to receive an update
     */
    public GameState getLatestGameState() {
        return latestGameState;
    }
    
    
    /**
     * Subscribes a new observer to receive game state information when sent by the game client. New observers can be
     * registered regardless of the running state of the server.
     *
     * @param observer the observer to register
     */
    public void registerObserver(GSIObserver observer) {
        if (LOGGER.isDebugEnabled())
            LOGGER.debug("New observer #{} registered for GSI server on port {}", Integer.toHexString(observer.hashCode()), server.getPort());
        
        observers.add(observer);
    }
    
    /**
     * Removes an observer from the list, and will no longer receive updates. Observers can be removed while the server
     * is running, although they may still receive updates for a short period while being removed.
     *
     * @param observer the observer to unsubscribe
     */
    public void removeObserver(GSIObserver observer) {
        if (LOGGER.isDebugEnabled())
            LOGGER.debug("Removing observer #{} from GSI server on port {}", Integer.toHexString(observer.hashCode()), server.getPort());
        
        observers.remove(observer);
    }
    
    /**
     * Notifies the registered observers of an updated state.
     *
     * @param state         the new game state information
     * @param context       the game state and request context
     */
    protected void notifyObservers(GameState state, GameStateContext context) {
        LOGGER.debug("Notifying {} observers of new GSI state from server on port {}", observers.size(), server.getPort());
        
        for (GSIObserver observer : observers) {
            observerExecutor.submit(
                    new LoggableTask(() -> observer.update(state, context)));
        }
    }
    
    
    /**
     * Starts the server on the configured network port and listens for game state information. This server is ran from
     * a newly issued thread, and can safely be called from the main application thread.
     *
     * @throws IllegalStateException if the server is already running
     * @throws IOException           if the configured port cannot be bound to
     */
    public void startServer() throws IOException {
        LOGGER.debug("Attempting to start GSI server on port {}...", server.getPort());
        
        if (server.isRunning())
            throw new IllegalStateException("The GSI server is already running.");
    
        latestTimestamps.clear();
        latestGameState = null;
        server.start();
        LOGGER.info("GSI server on port {} successfully started", server.getPort());
    }
    
    /**
     * Stops the server from listening for game state information, and frees the assigned network port.
     *
     * @throws IllegalStateException if the server is not currently running
     */
    public void stopServer() {
        LOGGER.debug("Attempting to stop GSI server running on port {}...", server.getPort());
        server.stop();
        LOGGER.info("GSI server on port {} successfully shut down", server.getPort());
    }
    
    /**
     * @return true if the server is currently running and listening
     */
    public boolean isServerRunning() {
        return server.isRunning();
    }
    
    /**
     * @return the port which the server will listen on
     */
    public int getServerPort() {
        return server.getPort();
    }
    
    /**
     * @return the address which the server will bind to, or null if it will bind to all local addresses
     */
    public InetAddress getServerBindingAddress() {
        return server.getBindAddress();
    }
    
    /**
     * @return an immutable map of required authentication tokens
     */
    public Map<String, String> getRequiredAuthTokens() {
        return requiredAuthTokens;
    }
    
    /**
     * @return whether game states with an older timestamp will be discarded
     */
    public boolean getDiscardOlderStates() {
        return discardOlderStates;
    }
    
    /**
     * @param discardOlderStates whether game states with an older timestamp will be discarded
     * @return this instance
     */
    public GSIServer setDiscardOlderStates(boolean discardOlderStates) {
        this.discardOlderStates = discardOlderStates;
        return this;
    }
    
    
    /**
     * Used for unit tests
     */
    ExecutorService getObserverExecutorService() {
        return observerExecutor;
    }
    
    
    /**
     * Handles a new JSON state and notifies the appropriate observers.
     */
    void handleStateUpdate(String json, InetAddress address) {
        JsonObject jsonObject = JsonParser.parseString(json).getAsJsonObject();
        
        //Parse auth tokens
        Map<String, String> authTokens = GSON.fromJson(jsonObject.getAsJsonObject("auth"),
                new TypeToken<Map<String, String>>() {}.getType());
        authTokens = (authTokens != null) ? authTokens : Collections.emptyMap();
        
        //Verify auth tokens
        for (Map.Entry<String, String> token : requiredAuthTokens.entrySet()) {
            String val = authTokens.get(token.getKey());
            if (!token.getValue().equals(val)) {
                LOGGER.debug("GSI state update rejected due to auth token mismatch (key '{}': expected '{}', "
                        + "got '{}')", token.getKey(), token.getValue(), val);
                return; //Invalid auth token(s), ignore
            }
        }
        
        GameState state = GSON.fromJson(jsonObject, GameState.class); //Parse game state
        GameStateContext context = new GameStateContext(this, latestGameState, address, authTokens, jsonObject);
        
        // Discard old game states
        if (!discardOlderStates
                || state.getProviderDetails() == null
                || state.getProviderDetails().getTimeStamp() <= 0
                || state.getProviderDetails().getTimeStamp() >= latestTimestamps.getOrDefault(address, 0)) {
            // Update latest state and timestamps
            latestGameState = state;
            latestTimestamps.put(address,
                    state.getProviderDetails() != null ? state.getProviderDetails().getTimeStamp() : 0);
    
            // Notify observers
            notifyObservers(state, context);
        } else {
            // Discard the state (and log)
            if (LOGGER.isDebugEnabled())
                LOGGER.debug("Discarding received game state due to outdated timestamp.");
        }
    }
    
    
    /**
     * Used for notifying observers and logging exceptions
     */
    private static class LoggableTask implements Runnable {
        Runnable task;
        LoggableTask(Runnable task) {
            this.task = task;
        }
        
        @Override
        public void run() {
            try {
                task.run();
            } catch (Exception e) {
                LOGGER.error("Uncaught exception in GSIServer observer notification", e);
            }
        }
    }
    
}
