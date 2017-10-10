package br.com.betogontijo.sbgcrawler;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Properties;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicInteger;

import com.mongodb.client.MongoCollection;

public class SbgDataSource {

	static final String INSERT_REFERENCE_QUERY = "INSERT INTO refs (uri) VALUES ";
	static final String SELECT_AND_REMOVE_REFERENCE_QUERY = "DELETE FROM refs LIMIT ? RETURNING uri";

	private AtomicInteger documentIdCounter = new AtomicInteger();
	private AtomicInteger domainIdCounter = new AtomicInteger();

	private ConnectionManager connectionManager;

	@SuppressWarnings("rawtypes")
	MongoCollection<Map> domainsDb;

	@SuppressWarnings("rawtypes")
	MongoCollection<Map> documentsDb;

	private Connection mariaDbConnection;

	private Queue<String> referencesBufferQueue;

	private static int bufferSize;

	private static int threads;

	{
		try {
			// Starts connectionFactory
			connectionManager = new ConnectionManager();

			// Get the connection for both document and domain collections
			domainsDb = connectionManager.getDomainsConnection();
			documentsDb = connectionManager.getDocumentsConnection();

			// Get the connection for references database
			setMariaDbConnection(connectionManager.getReferencesConnection());

			// Get last document and domain ID
			documentIdCounter.set((int) documentsDb.count());
			domainIdCounter.set((int) domainsDb.count());

			setReferencesBufferQueue(new ConcurrentSetQueue<String>());
			Properties properties = new Properties();
			properties.load(ClassLoader.getSystemResourceAsStream("sbgcrawler.properties"));
			setBufferSize(Integer.parseInt(properties.getProperty("environment.buffer.size")));
			setThreads(Integer.parseInt(properties.getProperty("environment.threads")));
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	static SbgDataSource dataSource;

	/**
	 * @return
	 */
	public static SbgDataSource getInstance() {
		if (dataSource == null) {
			dataSource = new SbgDataSource();
		}
		return dataSource;
	}

	/**
	 * @return
	 */
	private Connection getMariaDbConnection() {
		return mariaDbConnection;
	}

	/**
	 * @param mariaDbConnection
	 */
	private void setMariaDbConnection(Connection mariaDbConnection) {
		this.mariaDbConnection = mariaDbConnection;
	}

	/**
	 * @return
	 */
	public Queue<String> getReferencesBufferQueue() {
		return referencesBufferQueue;
	}

	/**
	 * @param referencesBufferQueue
	 */
	private void setReferencesBufferQueue(Queue<String> referencesBufferQueue) {
		this.referencesBufferQueue = referencesBufferQueue;
	}

	/**
	 * @return
	 */
	private static int getBufferSize() {
		return bufferSize;
	}

	/**
	 * @param bufferSize
	 */
	private static void setBufferSize(int bufferSize) {
		SbgDataSource.bufferSize = bufferSize;
	}

	/**
	 * @return
	 */
	private static int getThreads() {
		return threads;
	}

	/**
	 * @param threads
	 */
	private static void setThreads(int threads) {
		SbgDataSource.threads = threads;
	}

	/**
	 * @return
	 */
	public int getDocIdCounter() {
		return documentIdCounter.get();
	}

	/**
	 * @param document
	 * @param nextDocument
	 */
	public void updateDomainsDb(SbgMap<String, Object> domain, SbgMap<String, Object> nextDomain) {
		if (nextDomain.get("_id") == null) {
			nextDomain.put("_id", domainIdCounter.incrementAndGet());
			domainsDb.insertOne(nextDomain);
		} else {
			domainsDb.replaceOne(domain, nextDomain);
		}
	}

	/**
	 * @param document
	 * @param nextDocument
	 */
	public void updateDocumentsDb(SbgMap<String, Object> document, SbgMap<String, Object> nextDocument) {
		if (nextDocument.get("_id") == null) {
			nextDocument.put("_id", documentIdCounter.incrementAndGet());
			documentsDb.insertOne(nextDocument);
		} else {
			documentsDb.replaceOne(document, nextDocument);
		}
	}

	/**
	 * @param domain
	 * @return
	 */
	@SuppressWarnings("unchecked")
	public Domain findDomain(Domain domain) {
		Map<String, Object> domainMap = domainsDb.find(domain, SbgMap.class).first();
		Domain nextDomain = new Domain(domainMap, domain.getUri());
		return nextDomain;
	}

	/**
	 * @param sbgPage
	 * @param search
	 * @return
	 */
	@SuppressWarnings("unchecked")
	public SbgDocument findDocument(SbgDocument sbgPage, boolean search) {
		SbgDocument nextSbgPage = null;
		if (search) {
			Map<String, Object> sbgPageMap = documentsDb.find(sbgPage, SbgMap.class).first();
			nextSbgPage = new SbgDocument(sbgPageMap, sbgPage.getUri());
		} else {
			nextSbgPage = new SbgDocument(sbgPage.getUri());
		}
		return nextSbgPage;
	}

	/**
	 * @return
	 */
	private int getBufferPerThread() {
		return getBufferSize() / getThreads() * 2;
	}

	/**
	 * @param reference
	 */
	public void insertReference(List<String> reference) {
		getReferencesBufferQueue().addAll((reference));
		if (getReferencesBufferQueue().size() > getBufferSize()) {
			int n = getBufferPerThread();
			if (n > 0) {
				try {
					Statement createStatement = getMariaDbConnection().createStatement();
					while (n > 0) {
						StringBuilder builder = new StringBuilder(INSERT_REFERENCE_QUERY);
						for (int i = 1024000; builder.length() < i && n > 0; n--) {
							builder.append("('");
							builder.append(getReferencesBufferQueue().remove());
							builder.append("'),");
						}
						builder.deleteCharAt(builder.length() - 1);
						createStatement.addBatch(builder.toString());
					}
					createStatement.executeBatch();
				} catch (SQLException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				} catch (NoSuchElementException e1) {
				}
			}
		}
	}

	/**
	 * @return
	 */
	public String getReference() {
		// When reaches size of the buffer is time to fill it again
		if (documentIdCounter.get() > getBufferSize() && getReferencesBufferQueue().size() < getBufferPerThread()) {
			try {
				PreparedStatement prepareStatement = getMariaDbConnection()
						.prepareStatement(SELECT_AND_REMOVE_REFERENCE_QUERY);
				prepareStatement.setInt(1, getBufferPerThread());
				ResultSet executeQuery = prepareStatement.executeQuery();
				while (executeQuery.next()) {
					getReferencesBufferQueue().add(executeQuery.getString("uri"));
				}
			} catch (SQLException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}
		if (getReferencesBufferQueue().size() > 0) {
			return getReferencesBufferQueue().remove();
		} else {
			return null;
		}
	}

	/**
	 * @return
	 */
	public boolean hasReferences() {
		if (getReferencesBufferQueue().size() == 0) {
			String ref = getReference();
			if (ref == null) {
				return false;
			} else {
				getReferencesBufferQueue().add(ref);
				return true;
			}
		} else {
			return true;
		}
	}

}
