package br.com.betogontijo.sbgreader;

import java.io.Closeable;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.commons.io.IOUtils;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import com.mongodb.BasicDBObject;
import com.mongodb.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;

class SbgCrawler implements Closeable, Runnable {

	MongoClient mongoClient;

	@SuppressWarnings("rawtypes")
	MongoCollection<Map> domainDB;

	@SuppressWarnings("rawtypes")
	MongoCollection<Map> documentDB;

	private AtomicInteger docIdCounter = new AtomicInteger(1);

	static final String DOMAINS_COLLECTION_NAME = "domain";
	static final String DOCUMENTS_COLLECTION_NAME = "document";

	private Queue<String> references = new ConcurrentLinkedQueue<String>();

	SbgCrawler() {
		startUpMongoDb();
	}

	@SuppressWarnings("unchecked")
	private void startUpMongoDb() {
		// Get connection and database
		mongoClient = new MongoClient("localhost", 27017);
		MongoDatabase database = mongoClient.getDatabase("SbgDB");

		// Get the driver for both document and domain collections
		domainDB = database.getCollection(DOMAINS_COLLECTION_NAME, Map.class);
		documentDB = database.getCollection(DOCUMENTS_COLLECTION_NAME, Map.class);

		// Query to search for the last documentID
		BasicDBObject id = new BasicDBObject();
		id.put("_id", -1);
		Map<String, Object> maxId = documentDB.find().sort(id).first();
		if (maxId != null) {
			setDocIdCounter((Integer) maxId.get("_id"));
		}
	}

	public void crawl(String uri) throws IOException {
		// Query to stored domain
		Domain domainQuery = new Domain(Domain.getDomain(uri));
		// Load or create the domain for this document
		Domain domain = findDomain(domainQuery);

		// Try to retrieve robots.txt from this domain
		if (domain.getRobotsContent() == null) {
			domain.setRobotsContent(getRobotsCotent(domain.getUri()));
		}

		// Check if page is allowed
		if (!domain.isPageAllowed(uri)) {
			return;
		}

		// Query to stored document
		SbgDocument sbgDocumentQuery = new SbgDocument(uri);
		// Load or create the document
		SbgDocument sbgDocument = findPage(sbgDocumentQuery, domain.isLoadedInstance());
		// Check if still updated
		if (sbgDocument.isOutDated()) {
			return;
		}
		try {
			// Retrieve the HTML
			org.jsoup.nodes.Document doc = Jsoup.parse(IOUtils.toString(sbgDocument.getInputStream()));
			sbgDocument.setContent(doc.text());
			sbgDocument.setLastModified(System.currentTimeMillis());

			// Filter references
			Elements links = doc.select("[href]").not("[href~=(?i)\\.(png|jpe?g|css|gif|ico|js|json|mov)]")
					.not("[hreflang]");
			for (Element element : links) {
				String href = element.attr("abs:href").split("\\?")[0];
				try {
					// Parse full path reference uri
					href = UriUtils.pathToUri(href).toString();
					getReferences().add(href);
				} catch (Exception e) {
				}
			}
			// Update the db
			updateDB(domainDB, domainQuery, domain);
			updateDB(documentDB, sbgDocumentQuery, sbgDocument);
		} catch (URISyntaxException e1) {
			// Just ignore this exception?
		}
	}

	@SuppressWarnings("unchecked")
	private Domain findDomain(Domain domain) {
		Map<String, Object> domainMap = domainDB.find(domain, SbgMap.class).first();
		Domain nextDomain = new Domain(domainMap, domain.getUri());
		return nextDomain;
	}

	@SuppressWarnings("unchecked")
	private SbgDocument findPage(SbgDocument sbgPage, boolean search) {
		SbgDocument nextSbgPage = null;
		if (search) {
			Map<String, Object> sbgPageMap = documentDB.find(sbgPage, SbgMap.class).first();
			nextSbgPage = new SbgDocument(sbgPageMap, sbgPage.getPath());
		} else {
			nextSbgPage = new SbgDocument(sbgPage.getPath());
		}
		return nextSbgPage;
	}

	@SuppressWarnings("rawtypes")
	private void updateDB(MongoCollection<Map> collection, SbgMap<String, Object> document,
			SbgMap<String, Object> nextDocument) {
		if (nextDocument.get("_id") == null) {
			nextDocument.put("_id", docIdCounter.incrementAndGet());
			collection.insertOne(nextDocument);
		} else {
			collection.replaceOne(document, nextDocument);
		}
	}

	private byte[] getRobotsCotent(String domainUrl) {
		try {
			URL url = new URL("http://" + domainUrl + "/robots.txt");
			HttpURLConnection connection = (HttpURLConnection) url.openConnection();
			return IOUtils.toByteArray(connection.getInputStream());
		} catch (Exception e) {
			return null;
		}
	}

	public void close() {
		mongoClient.close();
	}

	public int getDocIdCounter() {
		return docIdCounter.get();
	}

	private void setDocIdCounter(int docIdCounter) {
		this.docIdCounter.set(docIdCounter);
	}

	public void run() {
		try {
			crawl(getReferences().remove());
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	public Queue<String> getReferences() {
		return references;
	}

}