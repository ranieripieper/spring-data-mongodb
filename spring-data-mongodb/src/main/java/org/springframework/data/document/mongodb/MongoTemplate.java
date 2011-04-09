/*
 * Copyright 2010-2011 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *			http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.data.document.mongodb;

import static org.springframework.data.document.mongodb.query.Criteria.whereId;

import java.beans.IntrospectionException;
import java.beans.PropertyDescriptor;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.mongodb.BasicDBObject;
import com.mongodb.CommandResult;
import com.mongodb.DB;
import com.mongodb.DBCollection;
import com.mongodb.DBCursor;
import com.mongodb.DBObject;
import com.mongodb.Mongo;
import com.mongodb.MongoException;
import com.mongodb.WriteConcern;
import com.mongodb.WriteResult;
import com.mongodb.util.JSON;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.bson.types.ObjectId;
import org.springframework.beans.ConfigurablePropertyAccessor;
import org.springframework.beans.PropertyAccessorFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.ApplicationEventPublisherAware;
import org.springframework.core.convert.ConversionFailedException;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.data.document.mongodb.MongoPropertyDescriptors.MongoPropertyDescriptor;
import org.springframework.data.document.mongodb.convert.MappingMongoConverter;
import org.springframework.data.document.mongodb.convert.MongoConverter;
import org.springframework.data.document.mongodb.convert.SimpleMongoConverter;
import org.springframework.data.document.mongodb.index.IndexDefinition;
import org.springframework.data.document.mongodb.mapping.MongoPersistentEntity;
import org.springframework.data.document.mongodb.mapping.event.AfterConvertEvent;
import org.springframework.data.document.mongodb.mapping.event.AfterLoadEvent;
import org.springframework.data.document.mongodb.mapping.event.AfterSaveEvent;
import org.springframework.data.document.mongodb.mapping.event.BeforeConvertEvent;
import org.springframework.data.document.mongodb.mapping.event.BeforeSaveEvent;
import org.springframework.data.document.mongodb.mapping.event.MongoMappingEvent;
import org.springframework.data.document.mongodb.query.Query;
import org.springframework.data.document.mongodb.query.Update;
import org.springframework.data.mapping.MappingBeanHelper;
import org.springframework.data.mapping.context.MappingContextAware;
import org.springframework.data.mapping.model.MappingContext;
import org.springframework.data.mapping.model.MappingException;
import org.springframework.data.mapping.model.PersistentEntity;
import org.springframework.data.mapping.model.PersistentProperty;
import org.springframework.jca.cci.core.ConnectionCallback;
import org.springframework.util.Assert;

/**
 * Primary implementation of {@link MongoOperations}.
 *
 * @author Thomas Risberg
 * @author Graeme Rocher
 * @author Mark Pollack
 * @author Oliver Gierke
 */
public class MongoTemplate implements InitializingBean, MongoOperations, ApplicationEventPublisherAware, MappingContextAware {

	private static final Log LOGGER = LogFactory.getLog(MongoTemplate.class);

	private static final String ID = "_id";

	/*
			* WriteConcern to be used for write operations if it has been specified. Otherwise
			* we should not use a WriteConcern defaulting to the one set for the DB or Collection.
			*/
	private WriteConcern writeConcern = null;

	/*
			* WriteResultChecking to be used for write operations if it has been specified. Otherwise
			* we should not do any checking.
			*/
	private WriteResultChecking writeResultChecking = WriteResultChecking.NONE;

	private MongoConverter mongoConverter;
	private MappingContext mappingContext;
	private final Mongo mongo;
	private final MongoExceptionTranslator exceptionTranslator = new MongoExceptionTranslator();

	private String defaultCollectionName;
	private String databaseName;
	private String username;
	private String password;
	private ApplicationEventPublisher eventPublisher;

	/**
	 * Constructor used for a basic template configuration
	 *
	 * @param mongo
	 * @param databaseName
	 */
	public MongoTemplate(Mongo mongo, String databaseName) {
		this(mongo, databaseName, null, null, null, null);
	}

	/**
	 * Constructor used for a basic template configuration with a default collection name
	 *
	 * @param mongo
	 * @param databaseName
	 * @param defaultCollectionName
	 */
	public MongoTemplate(Mongo mongo, String databaseName, String defaultCollectionName) {
		this(mongo, databaseName, defaultCollectionName, null, null, null);
	}

	/**
	 * Constructor used for a template configuration with a default collection name and a custom {@link org.springframework.data.document.mongodb.convert.MongoConverter}
	 *
	 * @param mongo
	 * @param databaseName
	 * @param defaultCollectionName
	 * @param mongoConverter
	 */
	public MongoTemplate(Mongo mongo, String databaseName, String defaultCollectionName, MongoConverter mongoConverter) {
		this(mongo, databaseName, defaultCollectionName, mongoConverter, null, null);
	}

	/**
	 * Constructor used for a template configuration with a default collection name and a custom {@link MongoConverter}
	 * and with a specific {@link com.mongodb.WriteConcern} to be used for all database write operations
	 *
	 * @param mongo
	 * @param databaseName
	 * @param defaultCollectionName
	 * @param mongoConverter
	 * @param writeConcern
	 * @param writeResultChecking
	 */
	MongoTemplate(Mongo mongo, String databaseName, String defaultCollectionName, MongoConverter mongoConverter, WriteConcern writeConcern, WriteResultChecking writeResultChecking) {

		Assert.notNull(mongo);
		Assert.notNull(databaseName);

		this.defaultCollectionName = defaultCollectionName;
		this.mongo = mongo;
		this.databaseName = databaseName;
		this.writeConcern = writeConcern;
		if (writeResultChecking != null) {
			this.writeResultChecking = writeResultChecking;
		}
		if (mongoConverter == null) {
		  SimpleMongoConverter smc = new SimpleMongoConverter();
		  smc.afterPropertiesSet();
		  setMongoConverter(smc);
		} else {
		  setMongoConverter(mongoConverter); 
		}
		//setMongoConverter(mongoConverter == null ? new SimpleMongoConverter() : mongoConverter);
	}

	public void setApplicationEventPublisher(ApplicationEventPublisher applicationEventPublisher) {
		this.eventPublisher = applicationEventPublisher;
	}

	public void setMappingContext(MappingContext mappingContext) {
		this.mappingContext = mappingContext;
	}

	/**
	 * Sets the username to use to connect to the Mongo database
	 *
	 * @param username The username to use
	 */
	public void setUsername(String username) {
		this.username = username;
	}

	/**
	 * Sets the password to use to authenticate with the Mongo database.
	 *
	 * @param password The password to use
	 */
	public void setPassword(String password) {

		this.password = password;
	}

	/**
	 * Sets the name of the default collection to be used.
	 *
	 * @param defaultCollectionName
	 */
	public void setDefaultCollectionName(String defaultCollectionName) {
		this.defaultCollectionName = defaultCollectionName;
	}

	/**
	 * Sets the database name to be used.
	 *
	 * @param databaseName
	 */
	public void setDatabaseName(String databaseName) {
		Assert.notNull(databaseName);
		this.databaseName = databaseName;
	}

	/**
	 * Returns the default {@link org.springframework.data.document.mongodb.convert.MongoConverter}.
	 *
	 * @return
	 */
	public MongoConverter getConverter() {
		return this.mongoConverter;
	}

	/* (non-Javadoc)
			* @see org.springframework.data.document.mongodb.MongoOperations#getDefaultCollectionName()
			*/
	public String getDefaultCollectionName() {
		return defaultCollectionName;
	}

	/* (non-Javadoc)
			* @see org.springframework.data.document.mongodb.MongoOperations#getDefaultCollection()
			*/
	public DBCollection getDefaultCollection() {

		return execute(new DbCallback<DBCollection>() {
			public DBCollection doInDB(DB db) throws MongoException, DataAccessException {
				return db.getCollection(getDefaultCollectionName());
			}
		});
	}

	/* (non-Javadoc)
			* @see org.springframework.data.document.mongodb.MongoOperations#executeCommand(java.lang.String)
			*/
	public CommandResult executeCommand(String jsonCommand) {
		return executeCommand((DBObject) JSON.parse(jsonCommand));
	}

	/* (non-Javadoc)
			* @see org.springframework.data.document.mongodb.MongoOperations#executeCommand(com.mongodb.DBObject)
			*/
	public CommandResult executeCommand(final DBObject command) {

		CommandResult result = execute(new DbCallback<CommandResult>() {
			public CommandResult doInDB(DB db) throws MongoException, DataAccessException {
				return db.command(command);
			}
		});

		String error = result.getErrorMessage();
		if (error != null) {
			// TODO: allow configuration of logging level / throw
			//	throw new InvalidDataAccessApiUsageException("Command execution of " +
			//			command.toString() + " failed: " + error);
			LOGGER.warn("Command execution of " +
					command.toString() + " failed: " + error);
		}
		return result;
	}

	/* (non-Javadoc)
			* @see org.springframework.data.document.mongodb.MongoOperations#execute(org.springframework.data.document.mongodb.DBCallback)
			*/
	public <T> T execute(DbCallback<T> action) {

		Assert.notNull(action);

		try {
			DB db = this.getDb();
			return action.doInDB(db);
		} catch (RuntimeException e) {
			throw potentiallyConvertRuntimeException(e);
		}
	}

	/* (non-Javadoc)
			* @see org.springframework.data.document.mongodb.MongoOperations#execute(org.springframework.data.document.mongodb.CollectionCallback)
			*/
	public <T> T execute(CollectionCallback<T> callback) {
		return execute(getDefaultCollectionName(), callback);
	}

	/* (non-Javadoc)
			* @see org.springframework.data.document.mongodb.MongoOperations#execute(org.springframework.data.document.mongodb.CollectionCallback, java.lang.String)
			*/
	public <T> T execute(String collectionName, CollectionCallback<T> callback) {

		Assert.notNull(callback);

		try {
			DBCollection collection = getDb().getCollection(collectionName);
			return callback.doInCollection(collection);
		} catch (RuntimeException e) {
			throw potentiallyConvertRuntimeException(e);
		}
	}

	/**
	 * Central callback executing method to do queries against the datastore that requires reading a single object from a
	 * collection of objects. It will take the following steps <ol> <li>Execute the given {@link ConnectionCallback} for a
	 * {@link DBObject}.</li> <li>Apply the given
	 * {@link DbObjectCallback} to each of the {@link DBObject}s to obtain the result.</li> <ol>
	 *
	 * @param <T>
	 * @param collectionCallback the callback to retrieve the {@link DBObject} with
	 * @param objectCallback		 the {@link DbObjectCallback} to transform {@link DBObject}s into the actual domain type
	 * @param collectionName		 the collection to be queried
	 * @return
	 */
	private <T> T execute(CollectionCallback<DBObject> collectionCallback,
												DbObjectCallback<T> objectCallback, String collectionName) {

		try {
			T result = objectCallback.doWith(collectionCallback.doInCollection(getCollection(collectionName)));
			return result;
		} catch (RuntimeException e) {
			throw potentiallyConvertRuntimeException(e);
		}
	}

	/**
	 * Central callback executing method to do queries against the datastore that requires reading a collection of
	 * objects. It will take the following steps <ol> <li>Execute the given {@link ConnectionCallback} for a
	 * {@link DBCursor}.</li> <li>Prepare that {@link DBCursor} with the given {@link CursorPreparer} (will be skipped
	 * if {@link CursorPreparer} is {@literal null}</li> <li>Iterate over the {@link DBCursor} and applies the given
	 * {@link DbObjectCallback} to each of the {@link DBObject}s collecting the actual result {@link List}.</li> <ol>
	 *
	 * @param <T>
	 * @param collectionCallback the callback to retrieve the {@link DBCursor} with
	 * @param preparer					 the {@link CursorPreparer} to potentially modify the {@link DBCursor} before ireating over it
	 * @param objectCallback		 the {@link DbObjectCallback} to transform {@link DBObject}s into the actual domain type
	 * @param collectionName		 the collection to be queried
	 * @return
	 */
	private <T> List<T> executeEach(CollectionCallback<DBCursor> collectionCallback, CursorPreparer preparer,
																	DbObjectCallback<T> objectCallback, String collectionName) {

		try {
			DBCursor cursor = collectionCallback.doInCollection(getCollection(collectionName));

			if (preparer != null) {
				cursor = preparer.prepare(cursor);
			}

			List<T> result = new ArrayList<T>();

			for (DBObject object : cursor) {
				result.add(objectCallback.doWith(object));
			}

			return result;
		} catch (RuntimeException e) {
			throw potentiallyConvertRuntimeException(e);
		}
	}

	/* (non-Javadoc)
			* @see org.springframework.data.document.mongodb.MongoOperations#executeInSession(org.springframework.data.document.mongodb.DBCallback)
			*/
	public <T> T executeInSession(final DbCallback<T> action) {

		return execute(new DbCallback<T>() {
			public T doInDB(DB db) throws MongoException, DataAccessException {
				try {
					db.requestStart();
					return action.doInDB(db);
				} finally {
					db.requestDone();
				}
			}
		});
	}

	/* (non-Javadoc)
			* @see org.springframework.data.document.mongodb.MongoOperations#createCollection(java.lang.String)
			*/
	public DBCollection createCollection(final String collectionName) {
		return doCreateCollection(collectionName, new BasicDBObject());
	}

	/* (non-Javadoc)
			* @see org.springframework.data.document.mongodb.MongoOperations#createCollection(java.lang.String, org.springframework.data.document.mongodb.CollectionOptions)
			*/
	public DBCollection createCollection(final String collectionName, final CollectionOptions collectionOptions) {
		return doCreateCollection(collectionName, convertToDbObject(collectionOptions));
	}

	/* (non-Javadoc)
			* @see org.springframework.data.document.mongodb.MongoOperations#getCollection(java.lang.String)
			*/
	public DBCollection getCollection(final String collectionName) {
		return execute(new DbCallback<DBCollection>() {
			public DBCollection doInDB(DB db) throws MongoException, DataAccessException {
				return db.getCollection(collectionName);
			}
		});
	}


	/* (non-Javadoc)
			* @see org.springframework.data.document.mongodb.MongoOperations#collectionExists(java.lang.String)
			*/
	public boolean collectionExists(final String collectionName) {
		return execute(new DbCallback<Boolean>() {
			public Boolean doInDB(DB db) throws MongoException, DataAccessException {
				return db.collectionExists(collectionName);
			}
		});
	}

	/* (non-Javadoc)
			* @see org.springframework.data.document.mongodb.MongoOperations#dropCollection(java.lang.String)
			*/
	public void dropCollection(String collectionName) {

		execute(collectionName, new CollectionCallback<Void>() {
			public Void doInCollection(DBCollection collection) throws MongoException, DataAccessException {
				collection.drop();
				return null;
			}
		});
	}

	// Indexing methods

	public void ensureIndex(IndexDefinition indexDefinition) {
		ensureIndex(getDefaultCollectionName(), indexDefinition);
	}

	public void ensureIndex(String collectionName, final IndexDefinition indexDefinition) {
		execute(collectionName, new CollectionCallback<Object>() {
			public Object doInCollection(DBCollection collection) throws MongoException, DataAccessException {
				DBObject indexOptions = indexDefinition.getIndexOptions();
				if (indexOptions != null) {
					collection.ensureIndex(indexDefinition.getIndexKeys(), indexOptions);
				} else {
					collection.ensureIndex(indexDefinition.getIndexKeys());
				}
				return null;
			}
		});
	}

	// Find methods that take a Query to express the query and that return a single object.

	public <T> T findOne(Query query, Class<T> targetClass) {
		return findOne(getEntityCollection(targetClass), query, targetClass);
	}

	public <T> T findOne(Query query, Class<T> targetClass,
											 MongoReader<T> reader) {
		return findOne(getEntityCollection(targetClass), query, targetClass, reader);
	}

	public <T> T findOne(String collectionName, Query query,
											 Class<T> targetClass) {
		return findOne(collectionName, query, targetClass, null);
	}

	public <T> T findOne(String collectionName, Query query,
											 Class<T> targetClass, MongoReader<T> reader) {
		return doFindOne(collectionName, query.getQueryObject(), query.getFieldsObject(), targetClass, reader);
	}

	// Find methods that take a Query to express the query and that return a List of objects.

	public <T> List<T> find(Query query, Class<T> targetClass) {
		return find(getEntityCollection(targetClass), query, targetClass);
	}

	public <T> List<T> find(Query query, Class<T> targetClass, MongoReader<T> reader) {
		return find(getEntityCollection(targetClass), query, targetClass, reader);
	}

	public <T> List<T> find(String collectionName, final Query query, Class<T> targetClass) {
		CursorPreparer cursorPreparer = null;
		if (query.getSkip() > 0 || query.getLimit() > 0 || query.getSortObject() != null) {
			cursorPreparer = new CursorPreparer() {

				public DBCursor prepare(DBCursor cursor) {
					DBCursor cursorToUse = cursor;
					try {
						if (query.getSkip() > 0) {
							cursorToUse = cursorToUse.skip(query.getSkip());
						}
						if (query.getLimit() > 0) {
							cursorToUse = cursorToUse.limit(query.getLimit());
						}
						if (query.getSortObject() != null) {
							cursorToUse = cursorToUse.sort(query.getSortObject());
						}
					} catch (RuntimeException e) {
						throw potentiallyConvertRuntimeException(e);
					}
					return cursorToUse;
				}
			};
		}
		return doFind(collectionName, query.getQueryObject(), query.getFieldsObject(), targetClass, cursorPreparer);
	}

	public <T> List<T> find(String collectionName, Query query, Class<T> targetClass, MongoReader<T> reader) {
		return doFind(collectionName, query.getQueryObject(), query.getFieldsObject(), targetClass, reader);
	}

	public <T> List<T> find(String collectionName, Query query,
													Class<T> targetClass, CursorPreparer preparer) {
		return doFind(collectionName, query.getQueryObject(), query.getFieldsObject(), targetClass, preparer);
	}

	// Find methods that take a Query to express the query and that return a single object that is
	// also removed from the collection in the database.

	public <T> T findAndRemove(Query query, Class<T> targetClass) {
		return findAndRemove(getEntityCollection(targetClass), query, targetClass);
	}

	public <T> T findAndRemove(Query query, Class<T> targetClass,
														 MongoReader<T> reader) {
		return findAndRemove(getEntityCollection(targetClass), query, targetClass, reader);
	}

	public <T> T findAndRemove(String collectionName, Query query,
														 Class<T> targetClass) {
		return findAndRemove(collectionName, query, targetClass, null);
	}

	public <T> T findAndRemove(String collectionName, Query query,
														 Class<T> targetClass, MongoReader<T> reader) {
		return doFindAndRemove(collectionName, query.getQueryObject(), query.getFieldsObject(), query.getSortObject(), targetClass, reader);
	}

	/* (non-Javadoc)
			* @see org.springframework.data.document.mongodb.MongoOperations#insert(java.lang.Object)
			*/
	public void insert(Object objectToSave) {
		insert(getEntityCollection(objectToSave), objectToSave);
	}

	/* (non-Javadoc)
			* @see org.springframework.data.document.mongodb.MongoOperations#insert(java.lang.String, java.lang.Object)
			*/
	public void insert(String collectionName, Object objectToSave) {
		insert(collectionName, objectToSave, this.mongoConverter);
	}

	/* (non-Javadoc)
			* @see org.springframework.data.document.mongodb.MongoOperations#insert(T, org.springframework.data.document.mongodb.MongoWriter)
			*/
	public <T> void insert(T objectToSave, MongoWriter<T> writer) {
		insert(getEntityCollection(objectToSave), objectToSave, writer);
	}

	/* (non-Javadoc)
			* @see org.springframework.data.document.mongodb.MongoOperations#insert(java.lang.String, T, org.springframework.data.document.mongodb.MongoWriter)
			*/
	public <T> void insert(String collectionName, T objectToSave, MongoWriter<T> writer) {
		BasicDBObject dbDoc = new BasicDBObject();

		maybeEmitEvent(new BeforeConvertEvent<T>(objectToSave));
		writer.write(objectToSave, dbDoc);

		maybeEmitEvent(new BeforeSaveEvent<T>(objectToSave, dbDoc));
		Object id = insertDBObject(collectionName, dbDoc);

		populateIdIfNecessary(objectToSave, id);
		maybeEmitEvent(new AfterSaveEvent<T>(objectToSave, dbDoc));
	}

	/* (non-Javadoc)
			* @see org.springframework.data.document.mongodb.MongoOperations#insertList(java.util.List)
			*/
	public void insertList(List<? extends Object> listToSave) {
		insertList(listToSave, mongoConverter);
	}

	/* (non-Javadoc)
			* @see org.springframework.data.document.mongodb.MongoOperations#insertList(java.lang.String, java.util.List)
			*/
	public void insertList(String collectionName, List<? extends Object> listToSave) {
		insertList(collectionName, listToSave, this.mongoConverter);
	}

	/* (non-Javadoc)
			* @see org.springframework.data.document.mongodb.MongoOperations#insertList(java.util.List, org.springframework.data.document.mongodb.MongoWriter)
			*/
	public <T> void insertList(List<? extends T> listToSave, MongoWriter<T> writer) {
		if (null != mappingContext) {
			Map<String, List<Object>> objs = new HashMap<String, List<Object>>();
			for (Object o : listToSave) {
				PersistentEntity<?> entity = mappingContext.getPersistentEntity(o.getClass());
				if (null != entity && entity instanceof MongoPersistentEntity) {
					@SuppressWarnings("unchecked")
					String coll = ((MongoPersistentEntity<T>) entity).getCollection();
					List<Object> objList = objs.get(coll);
					if (null == objList) {
						objList = new ArrayList<Object>();
						objs.put(coll, objList);
					}
					objList.add(o);
				} else {
					continue;
				}
			}
			for (Map.Entry<String, List<Object>> entry : objs.entrySet()) {
				insertList(entry.getKey(), entry.getValue());
			}
			return;
		}

		insertList(getDefaultCollectionName(), listToSave, writer);
	}

	/* (non-Javadoc)
			* @see org.springframework.data.document.mongodb.MongoOperations#insertList(java.lang.String, java.util.List, org.springframework.data.document.mongodb.MongoWriter)
			*/
	public <T> void insertList(String collectionName, List<? extends T> listToSave, MongoWriter<T> writer) {

		Assert.notNull(writer);

		List<DBObject> dbObjectList = new ArrayList<DBObject>();
		for (T o : listToSave) {
			BasicDBObject dbDoc = new BasicDBObject();

			maybeEmitEvent(new BeforeConvertEvent<T>(o));
			writer.write(o, dbDoc);

			maybeEmitEvent(new BeforeSaveEvent<T>(o, dbDoc));
			dbObjectList.add(dbDoc);
		}
		List<ObjectId> ids = insertDBObjectList(collectionName, dbObjectList);
		for (int i = 0; i < listToSave.size(); i++) {
			if (i < ids.size()) {
				T obj = listToSave.get(i);
				populateIdIfNecessary(obj, ids.get(i));
				maybeEmitEvent(new AfterSaveEvent<T>(obj, dbObjectList.get(i)));
			}
		}
	}

	/* (non-Javadoc)
			* @see org.springframework.data.document.mongodb.MongoOperations#save(java.lang.Object)
			*/
	public void save(Object objectToSave) {
		save(getEntityCollection(objectToSave), objectToSave);
	}

	/* (non-Javadoc)
			* @see org.springframework.data.document.mongodb.MongoOperations#save(java.lang.String, java.lang.Object)
			*/
	public void save(String collectionName, Object objectToSave) {
		save(collectionName, objectToSave, this.mongoConverter);
	}

	/* (non-Javadoc)
			* @see org.springframework.data.document.mongodb.MongoOperations#save(T, org.springframework.data.document.mongodb.MongoWriter)
			*/
	public <T> void save(T objectToSave, MongoWriter<T> writer) {
		save(getEntityCollection(objectToSave), objectToSave, writer);
	}

	/* (non-Javadoc)
			* @see org.springframework.data.document.mongodb.MongoOperations#save(java.lang.String, T, org.springframework.data.document.mongodb.MongoWriter)
			*/
	public <T> void save(String collectionName, T objectToSave, MongoWriter<T> writer) {
		BasicDBObject dbDoc = new BasicDBObject();

		maybeEmitEvent(new BeforeConvertEvent<T>(objectToSave));
		writer.write(objectToSave, dbDoc);

		maybeEmitEvent(new BeforeSaveEvent<T>(objectToSave, dbDoc));
		Object id = saveDBObject(collectionName, dbDoc);

		populateIdIfNecessary(objectToSave, id);
		maybeEmitEvent(new AfterSaveEvent<T>(objectToSave, dbDoc));
	}


	protected Object insertDBObject(String collectionName, final DBObject dbDoc) {

		if (dbDoc.keySet().isEmpty()) {
			return null;
		}

		//TODO: Need to move this to more central place
		if (dbDoc.containsField("_id")) {
			if (dbDoc.get("_id") instanceof String) {
				ObjectId oid = convertIdValue(this.mongoConverter, dbDoc.get("_id"));
				if (oid != null) {
					dbDoc.put("_id", oid);
				}
			}
		}
	    if (LOGGER.isDebugEnabled()) {
		    LOGGER.debug("insert DBObject containing fields: " + dbDoc.keySet());
		}
		return execute(collectionName, new CollectionCallback<Object>() {
			public Object doInCollection(DBCollection collection) throws MongoException, DataAccessException {
				if (writeConcern == null) {
					collection.insert(dbDoc);
				} else {
					collection.insert(dbDoc, writeConcern);
				}
				return dbDoc.get(ID);
			}
		});
	}

	protected List<ObjectId> insertDBObjectList(String collectionName, final List<DBObject> dbDocList) {

		if (dbDocList.isEmpty()) {
			return Collections.emptyList();
		}

		//TODO: Need to move this to more central place
		for (DBObject dbDoc : dbDocList) {
			if (dbDoc.containsField("_id")) {
				if (dbDoc.get("_id") instanceof String) {
					ObjectId oid = convertIdValue(this.mongoConverter, dbDoc.get("_id"));
					if (oid != null) {
						dbDoc.put("_id", oid);
					}
				}
			}
		}
	    if (LOGGER.isDebugEnabled()) {
		    LOGGER.debug("insert list of DBObjects containing " + dbDocList.size() + " items");
		}
		execute(collectionName, new CollectionCallback<Void>() {
			public Void doInCollection(DBCollection collection) throws MongoException, DataAccessException {
				if (writeConcern == null) {
					collection.insert(dbDocList);
				} else {
					collection.insert(dbDocList.toArray((DBObject[]) new BasicDBObject[dbDocList.size()]), writeConcern);
				}
				return null;
			}
		});

		List<ObjectId> ids = new ArrayList<ObjectId>();
		for (DBObject dbo : dbDocList) {
			Object id = dbo.get(ID);
			if (id instanceof ObjectId) {
				ids.add((ObjectId) id);
			} else {
				// no id was generated
				ids.add(null);
			}
		}
		return ids;
	}

	protected Object saveDBObject(String collectionName, final DBObject dbDoc) {

		if (dbDoc.keySet().isEmpty()) {
			return null;
		}

		//TODO: Need to move this to more central place
		if (dbDoc.containsField("_id")) {
			if (dbDoc.get("_id") instanceof String) {
				ObjectId oid = convertIdValue(this.mongoConverter, dbDoc.get("_id"));
				if (oid != null) {
					dbDoc.put("_id", oid);
				}
			}
		}
	    if (LOGGER.isDebugEnabled()) {
		    LOGGER.debug("save DBObject containing fields: " + dbDoc.keySet());
		}
		return execute(collectionName, new CollectionCallback<Object>() {
			public Object doInCollection(DBCollection collection) throws MongoException, DataAccessException {
				if (writeConcern == null) {
					collection.save(dbDoc);
				} else {
					collection.save(dbDoc, writeConcern);
				}
				return dbDoc.get(ID);
			}
		});
	}

	/* (non-Javadoc)
			* @see org.springframework.data.document.mongodb.MongoOperations#updateFirst(com.mongodb.DBObject, com.mongodb.DBObject)
			*/
	public WriteResult updateFirst(Query query, Update update) {
		return updateFirst(getRequiredDefaultCollectionName(), query, update);
	}

	/* (non-Javadoc)
			* @see org.springframework.data.document.mongodb.MongoOperations#updateFirst(java.lang.String, com.mongodb.DBObject, com.mongodb.DBObject)
			*/
	public WriteResult updateFirst(String collectionName, final Query query, final Update update) {
		return execute(collectionName, new CollectionCallback<WriteResult>() {
			public WriteResult doInCollection(DBCollection collection) throws MongoException, DataAccessException {
				WriteResult wr;
				if (writeConcern == null) {
					wr = collection.update(query.getQueryObject(), update.getUpdateObject());
				} else {
					wr = collection.update(query.getQueryObject(), update.getUpdateObject(), false, false, writeConcern);
				}
				handleAnyWriteResultErrors(wr, query.getQueryObject(), "update with '" + update.getUpdateObject() + "'");
				return wr;
			}
		});
	}

	/* (non-Javadoc)
			* @see org.springframework.data.document.mongodb.MongoOperations#updateMulti(com.mongodb.DBObject, com.mongodb.DBObject)
			*/
	public WriteResult updateMulti(Query query, Update update) {
		return updateMulti(getRequiredDefaultCollectionName(), query, update);
	}

	/* (non-Javadoc)
			* @see org.springframework.data.document.mongodb.MongoOperations#updateMulti(java.lang.String, com.mongodb.DBObject, com.mongodb.DBObject)
			*/
	public WriteResult updateMulti(String collectionName, final Query query, final Update update) {
		return execute(collectionName, new CollectionCallback<WriteResult>() {
			public WriteResult doInCollection(DBCollection collection) throws MongoException, DataAccessException {
				WriteResult wr = null;
				if (writeConcern == null) {
					wr = collection.updateMulti(query.getQueryObject(), update.getUpdateObject());
				} else {
					wr = collection.update(query.getQueryObject(), update.getUpdateObject(), false, true, writeConcern);
				}
				handleAnyWriteResultErrors(wr, query.getQueryObject(), "update with '" + update.getUpdateObject() + "'");
				return wr;
			}
		});
	}

	/* (non-Javadoc)
			* @see org.springframework.data.document.mongodb.MongoOperations#remove(com.mongodb.DBObject)
			*/
	public void remove(Query query) {
		remove(query, null);
	}
	
	public void remove(Object object) {
	  Object idValue = this.getIdValue(object);
	  remove(new Query(whereId().is(idValue)), object.getClass());
	}
	
	public <T> void remove(Query query, Class<T> targetClass) {
	   remove(getEntityCollection(targetClass), query, targetClass);
	}

	public <T> void remove(String collectionName, final Query query, Class<T> targetClass) { 
	   if (query == null) {
	      throw new InvalidDataAccessApiUsageException("Query passed in to remove can't be null");
	    }
	    final DBObject queryObject = query.getQueryObject();
	    if (targetClass == null) {
	      substituteMappedIdIfNecessary(queryObject);
	    } else {
	      substituteMappedIdIfNecessary(queryObject, targetClass, this.mongoConverter);
	    }
	    if (LOGGER.isDebugEnabled()) {
	      LOGGER.debug("remove using query: " + queryObject);
	    }
	    execute(collectionName, new CollectionCallback<Void>() {
	      public Void doInCollection(DBCollection collection) throws MongoException, DataAccessException {
	        WriteResult wr = null;
	        if (writeConcern == null) {
	          wr = collection.remove(queryObject);
	        } else {
	          wr = collection.remove(queryObject, writeConcern);
	        }
	        handleAnyWriteResultErrors(wr, queryObject, "remove");
	        return null;
	      }
	    });
	}
	
	/* (non-Javadoc)
			* @see org.springframework.data.document.mongodb.MongoOperations#remove(java.lang.String, com.mongodb.DBObject)
			*/
	public void remove(String collectionName, final Query query) {
	  remove(collectionName, query, null);
	}


	/* (non-Javadoc)
			* @see org.springframework.data.document.mongodb.MongoOperations#getCollection(java.lang.Class)
			*/
	public <T> List<T> getCollection(Class<T> targetClass) {
		return executeEach(new FindCallback(null), null, new ReadDbObjectCallback<T>(mongoConverter, targetClass),
				getDefaultCollectionName());
	}

	public <T> List<T> getCollection(String collectionName, Class<T> targetClass) {
		return executeEach(new FindCallback(null), null, new ReadDbObjectCallback<T>(mongoConverter, targetClass),
				collectionName);
	}

	public Set<String> getCollectionNames() {
		return execute(new DbCallback<Set<String>>() {
			public Set<String> doInDB(DB db) throws MongoException, DataAccessException {
				return db.getCollectionNames();
			}
		});
	}

	public <T> List<T> getCollection(String collectionName, Class<T> targetClass, MongoReader<T> reader) {
		return executeEach(new FindCallback(null), null, new ReadDbObjectCallback<T>(reader, targetClass),
				collectionName);
	}

	public DB getDb() {
		return MongoDbUtils.getDB(mongo, databaseName, username, password == null ? null : password.toCharArray());
	}

	protected <T> void maybeEmitEvent(MongoMappingEvent<T> event) {
		if (null != eventPublisher) {
			eventPublisher.publishEvent(event);
		}
	}

	/**
	 * Create the specified collection using the provided options
	 *
	 * @param collectionName
	 * @param collectionOptions
	 * @return the collection that was created
	 */
	protected DBCollection doCreateCollection(final String collectionName, final DBObject collectionOptions) {
		return execute(new DbCallback<DBCollection>() {
			public DBCollection doInDB(DB db) throws MongoException, DataAccessException {
				DBCollection coll = db.createCollection(collectionName, collectionOptions);
				// TODO: Emit a collection created event
				return coll;
			}
		});
	}

	/**
	 * Map the results of an ad-hoc query on the default MongoDB collection to an object using the provided MongoReader
	 * <p/>
	 * The query document is specified as a standard DBObject and so is the fields specification.
	 *
	 * @param collectionName name of the collection to retrieve the objects from
	 * @param query					the query document that specifies the criteria used to find a record
	 * @param fields				 the document that specifies the fields to be returned
	 * @param targetClass		the parameterized type of the returned list.
	 * @param reader				 the MongoReader to convert from DBObject to an object.
	 * @return the List of converted objects.
	 */
	protected <T> T doFindOne(String collectionName, DBObject query, DBObject fields, Class<T> targetClass, MongoReader<T> reader) {
		MongoReader<? super T> readerToUse = reader;
		if (readerToUse == null) {
			readerToUse = this.mongoConverter;
		}
		substituteMappedIdIfNecessary(query, targetClass, readerToUse);
		return execute(new FindOneCallback(query, fields), new ReadDbObjectCallback<T>(readerToUse, targetClass),
				collectionName);
	}

	/**
	 * Map the results of an ad-hoc query on the default MongoDB collection to a List of the specified type.
	 * <p/>
	 * The object is converted from the MongoDB native representation using an instance of
	 * {@see MongoConverter}.	 Unless configured otherwise, an
	 * instance of SimpleMongoConverter will be used.
	 * <p/>
	 * The query document is specified as a standard DBObject and so is the fields specification.
	 * <p/>
	 * Can be overridden by subclasses.
	 *
	 * @param collectionName name of the collection to retrieve the objects from
	 * @param query					the query document that specifies the criteria used to find a record
	 * @param fields				 the document that specifies the fields to be returned
	 * @param targetClass		the parameterized type of the returned list.
	 * @param preparer			 allows for customization of the DBCursor used when iterating over the result set,
	 *                       (apply limits, skips and so on).
	 * @return the List of converted objects.
	 */
	protected <T> List<T> doFind(String collectionName, DBObject query, DBObject fields, Class<T> targetClass, CursorPreparer preparer) {
		substituteMappedIdIfNecessary(query, targetClass, mongoConverter);
		if (LOGGER.isDebugEnabled()) {
			LOGGER.debug("find using query: " + query + " fields: " + fields + " for class: " + targetClass);
		}
		return executeEach(new FindCallback(query, fields), preparer, new ReadDbObjectCallback<T>(mongoConverter, targetClass),
				collectionName);
	}

	/**
	 * Map the results of an ad-hoc query on the default MongoDB collection to a List using the provided MongoReader
	 * <p/>
	 * The query document is specified as a standard DBObject and so is the fields specification.
	 *
	 * @param collectionName name of the collection to retrieve the objects from
	 * @param query					the query document that specifies the criteria used to find a record
	 * @param fields				 the document that specifies the fields to be returned
	 * @param targetClass		the parameterized type of the returned list.
	 * @param reader				 the MongoReader to convert from DBObject to an object.
	 * @return the List of converted objects.
	 */
	protected <T> List<T> doFind(String collectionName, DBObject query, DBObject fields, Class<T> targetClass, MongoReader<T> reader) {
		substituteMappedIdIfNecessary(query, targetClass, reader);
		if (LOGGER.isDebugEnabled()) {
			LOGGER.debug("find using query: " + query + " fields: " + fields + " for class: " + targetClass);
		}
		return executeEach(new FindCallback(query, fields), null, new ReadDbObjectCallback<T>(reader, targetClass),
				collectionName);
	}

	protected DBObject convertToDbObject(CollectionOptions collectionOptions) {
		DBObject dbo = new BasicDBObject();
		if (collectionOptions != null) {
			if (collectionOptions.getCapped() != null) {
				dbo.put("capped", collectionOptions.getCapped().booleanValue());
			}
			if (collectionOptions.getSize() != null) {
				dbo.put("size", collectionOptions.getSize().intValue());
			}
			if (collectionOptions.getMaxDocuments() != null) {
				dbo.put("max", collectionOptions.getMaxDocuments().intValue());
			}
		}
		return dbo;
	}

	/**
	 * Map the results of an ad-hoc query on the default MongoDB collection to an object using the provided MongoReader
	 * The first document that matches the query is returned and also removed from the collection in the database.
	 * <p/>
	 * The query document is specified as a standard DBObject and so is the fields specification.
	 *
	 * @param collectionName name of the collection to retrieve the objects from
	 * @param query					the query document that specifies the criteria used to find a record
	 * @param targetClass		the parameterized type of the returned list.
	 * @param reader				 the MongoReader to convert from DBObject to an object.
	 * @return the List of converted objects.
	 */
	protected <T> T doFindAndRemove(String collectionName, DBObject query, DBObject fields, DBObject sort, Class<T> targetClass, MongoReader<T> reader) {
		MongoReader<? super T> readerToUse = reader;
		if (readerToUse == null) {
			readerToUse = this.mongoConverter;
		}
		substituteMappedIdIfNecessary(query, targetClass, readerToUse);
		if (LOGGER.isDebugEnabled()) {
			LOGGER.debug("findAndRemove using query: " + query + " fields: " + fields + " sort: " + sort + " for class: " + targetClass);
		}
		return execute(new FindAndRemoveCallback(query, fields, sort), new ReadDbObjectCallback<T>(readerToUse, targetClass),
				collectionName);
	}

	protected Object getIdValue(Object object) {
	  if (null != mappingContext) {
	    PersistentEntity<?> entity = mappingContext.getPersistentEntity(object.getClass());
	    if (null != entity) {
        PersistentProperty idProp = entity.getIdProperty();
        if (null != idProp) {
          try {
            return MappingBeanHelper.getProperty(object, idProp, Object.class, true);            
          } catch (IllegalAccessException e) {
            throw new MappingException(e.getMessage(), e);
          } catch (InvocationTargetException e) {
            throw new MappingException(e.getMessage(), e);
          }
        }
      }	    
	  }
	  
	  ConfigurablePropertyAccessor bw = PropertyAccessorFactory.forDirectFieldAccess(object);
	  MongoPropertyDescriptor idDescriptor = new MongoPropertyDescriptors(object.getClass()).getIdDescriptor();

	  if (idDescriptor == null) {
	    return null;
	  }
	  return bw.getPropertyValue(idDescriptor.getName());
  
	}
	/**
	 * Populates the id property of the saved object, if it's not set already.
	 *
	 * @param savedObject
	 * @param id
	 */
	protected void populateIdIfNecessary(Object savedObject, Object id) {

		if (id == null) {
			return;
		}

		if (null != mappingContext) {
			PersistentEntity<?> entity = mappingContext.getPersistentEntity(savedObject.getClass());
			if (null != entity) {
				PersistentProperty idProp = entity.getIdProperty();
				if (null != idProp) {
					try {
						MappingBeanHelper.setProperty(savedObject, idProp, id);
						return;
					} catch (IllegalAccessException e) {
						throw new MappingException(e.getMessage(), e);
					} catch (InvocationTargetException e) {
						throw new MappingException(e.getMessage(), e);
					}
				}
			}
		}

		ConfigurablePropertyAccessor bw = PropertyAccessorFactory.forDirectFieldAccess(savedObject);
		MongoPropertyDescriptor idDescriptor = new MongoPropertyDescriptors(savedObject.getClass()).getIdDescriptor();

		if (idDescriptor == null) {
			return;
		}

		if (bw.getPropertyValue(idDescriptor.getName()) == null) {
			Object target = null;
			if (id instanceof ObjectId) {
				target = this.mongoConverter.convertObjectId((ObjectId) id, idDescriptor.getPropertyType());
			} else {
				target = id;
			}
			bw.setPropertyValue(idDescriptor.getName(), target);
		}
	}

	/**
	 * Substitutes the id key if it is found in he query. Any 'id' keys will be replaced with '_id' and the value converted
	 * to an ObjectId if possible. This conversion should match the way that the id fields are converted during read
	 * operations.
	 *
	 * @param query
	 * @param targetClass
	 * @param reader
	 */
	protected void substituteMappedIdIfNecessary(DBObject query, Class<?> targetClass, MongoReader<?> reader) {
		MongoConverter converter = null;
		if (reader instanceof SimpleMongoConverter) {
			converter = (MongoConverter) reader;
		}
		else if (reader instanceof MappingMongoConverter) {
			converter = (MappingMongoConverter) reader;
		}
		else {
			return;
		}
		String idKey = null;
		if (query.containsField("id")) {
			idKey = "id";
		}
		if (query.containsField("_id")) {
			idKey = "_id";
		}
		if (idKey == null) {
			// no ids in this query
			return;
		}
		MongoPropertyDescriptor descriptor;
		try {
			MongoPropertyDescriptor mpd = new MongoPropertyDescriptor(new PropertyDescriptor(idKey, targetClass), targetClass);
			descriptor = mpd;
		} catch (IntrospectionException e) {
			// no property descriptor for this key - try the other
			try {
				String theOtherIdKey = "id".equals(idKey) ? "_id" : "id";
				MongoPropertyDescriptor mpd2 = new MongoPropertyDescriptor(new PropertyDescriptor(theOtherIdKey, targetClass), targetClass);
				descriptor = mpd2;
			} catch (IntrospectionException e2) {
				// no property descriptor for this key either - bail
				return;
			}
		}
		if (descriptor.isIdProperty() && descriptor.isOfIdType()) {
			Object value = query.get(idKey);
			if (value instanceof DBObject) {
				DBObject dbo = (DBObject) value;
				if (dbo.containsField("$in")) {
					List<Object> ids = new ArrayList<Object>();
					int count = 0;
					for (Object o : (Object[])dbo.get("$in")) {
						count++;
						ObjectId newValue = convertIdValue(converter, o);
						if (newValue != null) {
							ids.add(newValue);
						}
					}
					if (ids.size() > 0 && ids.size() != count) {
						throw new InvalidDataAccessApiUsageException("Inconsistent set of id values provided " + 
								Arrays.asList((Object[])dbo.get("$in")));
					}
					if (ids.size() > 0) {
						dbo.removeField("$in");
						dbo.put("$in", ids.toArray());
					}
				}
				query.removeField(idKey);
				query.put(MongoPropertyDescriptor.ID_KEY, value);
			}
			else {
				ObjectId newValue = convertIdValue(converter, value);
				query.removeField(idKey);
				if (newValue != null) {
					query.put(MongoPropertyDescriptor.ID_KEY, newValue);
				} else {
					query.put(MongoPropertyDescriptor.ID_KEY, value);
				}
			}
		}
	}

	private ObjectId convertIdValue(MongoConverter converter, Object value) {
		ObjectId newValue = null;
		try {
			if (value instanceof String && ObjectId.isValid((String) value)) {
				newValue = converter.convertObjectId(value);
			}
		} catch (ConversionFailedException iae) {
			LOGGER.warn("Unable to convert the String " + value + " to an ObjectId");
		}
		return newValue;
	}

	/**
	 * Substitutes the id key if it is found in he query. Any 'id' keys will be replaced with '_id'. No conversion
	 * of the value to an ObjectId is possible since we don't have access to a targetClass or a converter. This 
	 * means the value has to be of the correct form.
	 *
	 * @param query
	 */
	protected void substituteMappedIdIfNecessary(DBObject query) {
		String idKey = null;
		if (query.containsField("id")) {
			idKey = "id";
		}
		if (query.containsField("_id")) {
			idKey = "_id";
		}
		if (idKey == null) {
			// no ids in this query
			return;
		}
		if (!idKey.equals(MongoPropertyDescriptor.ID_KEY)) {
			Object value = query.get(idKey);
			query.removeField(idKey);
			query.put(MongoPropertyDescriptor.ID_KEY, value);
		}
	}

	private String getRequiredDefaultCollectionName() {
		String name = getDefaultCollectionName();
		if (name == null) {
			throw new IllegalStateException(
					"No 'defaultCollection' or 'defaultCollectionName' specified. Check configuration of MongoTemplate.");
		}
		return name;
	}

	private <T> String getEntityCollection(T obj) {
		if (null != obj) {
			return getEntityCollection(obj.getClass());
		}

		return null;
	}

	private <T> String getEntityCollection(Class<T> clazz) {
		if (null != mappingContext) {
			PersistentEntity<T> entity = mappingContext.getPersistentEntity(clazz);
			if (entity == null) {
				entity = mappingContext.addPersistentEntity(clazz);
			}
			if (null != entity && entity instanceof MongoPersistentEntity) {
				return ((MongoPersistentEntity<T>) entity).getCollection();
			}
		}
		// Otherwise, return the default for this template.
		return getRequiredDefaultCollectionName();
	}

	/**
	 * Checks and handles any errors.
	 * <p/>
	 * TODO: current implementation logs errors - will be configurable to log warning, errors or
	 * throw exception in later versions
	 */
	private void handleAnyWriteResultErrors(WriteResult wr, DBObject query, String operation) {
		if (WriteResultChecking.NONE == this.writeResultChecking) {
			return;
		}
		String error = wr.getError();
		int n = wr.getN();
		if (error != null) {
			String message = "Execution of '" + operation +
					(query == null ? "" : "' using '" + query.toString() + "' query") + " failed: " + error;
			if (WriteResultChecking.EXCEPTION == this.writeResultChecking) {
				throw new DataIntegrityViolationException(message);
			} else {
				LOGGER.error(message);
			}
		} else if (n == 0) {
			String message = "Execution of '" + operation +
					(query == null ? "" : "' using '" + query.toString() + "' query") + " did not succeed: 0 documents updated";
			if (WriteResultChecking.EXCEPTION == this.writeResultChecking) {
				throw new DataIntegrityViolationException(message);
			} else {
				LOGGER.warn(message);
			}
		}

	}

	/**
	 * Tries to convert the given {@link RuntimeException} into a {@link DataAccessException} but returns the original
	 * exception if the conversation failed. Thus allows safe rethrowing of the return value.
	 *
	 * @param ex
	 * @return
	 */
	private RuntimeException potentiallyConvertRuntimeException(RuntimeException ex) {
		RuntimeException resolved = this.exceptionTranslator.translateExceptionIfPossible(ex);
		return resolved == null ? ex : resolved;
	}

	private void initializeMappingMongoConverter(MappingMongoConverter converter) {
		converter.setMongo(mongo);
		converter.setDefaultDatabase(databaseName);
	}

	/*
		* (non-Javadoc)
		* @see org.springframework.beans.factory.InitializingBean#afterPropertiesSet()
		*/
	public void afterPropertiesSet() {
		if (this.getDefaultCollectionName() != null) {
			if (!collectionExists(getDefaultCollectionName())) {
				createCollection(getDefaultCollectionName(), null);
			}
		}
	}


	/**
	 * Simple {@link CollectionCallback} that takes a query {@link DBObject} plus an optional fields specification
	 * {@link DBObject} and executes that against the {@link DBCollection}.
	 *
	 * @author Oliver Gierke
	 * @author Thomas Risberg
	 */
	private static class FindOneCallback implements CollectionCallback<DBObject> {

		private final DBObject query;

		private final DBObject fields;

		public FindOneCallback(DBObject query, DBObject fields) {
			this.query = query;
			this.fields = fields;
		}

		public DBObject doInCollection(DBCollection collection) throws MongoException, DataAccessException {
			if (fields == null) {
			   if (LOGGER.isDebugEnabled()) {
			      LOGGER.debug("findOne using query: " + query + " in db.collection: " + collection.getFullName());
			    }
				return collection.findOne(query);
			} else {
			   if (LOGGER.isDebugEnabled()) {
			      LOGGER.debug("findOne using query: " + query + " fields: " + fields + " in db.collection: " + collection.getFullName());
			    }
				return collection.findOne(query, fields);
			}
		}
	}

	/**
	 * Simple {@link CollectionCallback} that takes a query {@link DBObject} plus an optional fields specification
	 * {@link DBObject} and executes that against the {@link DBCollection}.
	 *
	 * @author Oliver Gierke
	 * @author Thomas Risberg
	 */
	private static class FindCallback implements CollectionCallback<DBCursor> {

		private final DBObject query;

		private final DBObject fields;

		public FindCallback(DBObject query) {
			this(query, null);
		}

		public FindCallback(DBObject query, DBObject fields) {
			this.query = query;
			this.fields = fields;
		}

		public DBCursor doInCollection(DBCollection collection) throws MongoException, DataAccessException {
			if (fields == null) {
				return collection.find(query);
			} else {
				return collection.find(query, fields);
			}
		}
	}

	/**
	 * Simple {@link CollectionCallback} that takes a query {@link DBObject} plus an optional fields specification
	 * {@link DBObject} and executes that against the {@link DBCollection}.
	 *
	 * @author Thomas Risberg
	 */
	private static class FindAndRemoveCallback implements CollectionCallback<DBObject> {

		private final DBObject query;

		private final DBObject fields;

		private final DBObject sort;

		public FindAndRemoveCallback(DBObject query, DBObject fields, DBObject sort) {
			this.query = query;
			this.fields = fields;
			this.sort = sort;
		}

		public DBObject doInCollection(DBCollection collection) throws MongoException, DataAccessException {
			return collection.findAndModify(query, fields, sort, true, null, false, false);
		}
	}

	/**
	 * Simple internal callback to allow operations on a {@link DBObject}.
	 *
	 * @author Oliver Gierke
	 */

	private interface DbObjectCallback<T> {

		T doWith(DBObject object);
	}

	/**
	 * Simple {@link DbObjectCallback} that will transform {@link DBObject} into the given target type using the given
	 * {@link MongoReader}.
	 *
	 * @author Oliver Gierke
	 */
	private class ReadDbObjectCallback<T> implements DbObjectCallback<T> {

		private final MongoReader<? super T> reader;
		private final Class<T> type;

		public ReadDbObjectCallback(MongoReader<? super T> reader, Class<T> type) {
			this.reader = reader;
			this.type = type;
		}

		public T doWith(DBObject object) {
			if (null != object) {
				maybeEmitEvent(new AfterLoadEvent<DBObject>(object));
			}
			T source = reader.read(type, object);
			if (null != source) {
				maybeEmitEvent(new AfterConvertEvent<T>(object, source));
			}
			return source;
		}
	}

	public void setMongoConverter(MongoConverter converter) {
		this.mongoConverter = converter;
		if (null != converter && converter instanceof MappingMongoConverter) {
			initializeMappingMongoConverter((MappingMongoConverter) mongoConverter);
		}
	}

	public void setWriteResultChecking(WriteResultChecking resultChecking) {
		this.writeResultChecking = resultChecking;
	}

	public void setWriteConcern(WriteConcern writeConcern) {
		this.writeConcern = writeConcern;
	}

}
